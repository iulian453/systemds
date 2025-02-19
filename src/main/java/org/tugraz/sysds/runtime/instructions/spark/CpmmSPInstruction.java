/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.tugraz.sysds.runtime.instructions.spark;

import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.function.Function;
import org.apache.spark.api.java.function.PairFunction;
import org.tugraz.sysds.hops.AggBinaryOp.SparkAggType;
import org.tugraz.sysds.runtime.DMLRuntimeException;
import org.tugraz.sysds.runtime.controlprogram.context.ExecutionContext;
import org.tugraz.sysds.runtime.controlprogram.context.SparkExecutionContext;
import org.tugraz.sysds.runtime.functionobjects.Multiply;
import org.tugraz.sysds.runtime.functionobjects.Plus;
import org.tugraz.sysds.runtime.functionobjects.SwapIndex;
import org.tugraz.sysds.runtime.instructions.InstructionUtils;
import org.tugraz.sysds.runtime.instructions.cp.CPOperand;
import org.tugraz.sysds.runtime.instructions.spark.functions.FilterNonEmptyBlocksFunction;
import org.tugraz.sysds.runtime.instructions.spark.functions.FilterNonEmptyBlocksFunction2;
import org.tugraz.sysds.runtime.instructions.spark.functions.ReorgMapFunction;
import org.tugraz.sysds.runtime.instructions.spark.utils.RDDAggregateUtils;
import org.tugraz.sysds.runtime.instructions.spark.utils.SparkUtils;
import org.tugraz.sysds.runtime.matrix.data.MatrixBlock;
import org.tugraz.sysds.runtime.matrix.data.MatrixIndexes;
import org.tugraz.sysds.runtime.matrix.data.OperationsOnMatrixValues;
import org.tugraz.sysds.runtime.matrix.mapred.IndexedMatrixValue;
import org.tugraz.sysds.runtime.matrix.operators.AggregateBinaryOperator;
import org.tugraz.sysds.runtime.matrix.operators.AggregateOperator;
import org.tugraz.sysds.runtime.matrix.operators.Operator;
import org.tugraz.sysds.runtime.matrix.operators.ReorgOperator;
import org.tugraz.sysds.runtime.meta.MatrixCharacteristics;

import scala.Tuple2;

/**
 * Cpmm: cross-product matrix multiplication operation (distributed matrix multiply
 * by join over common dimension and subsequent aggregation of partial results).
 * 
 * NOTE: There is additional optimization potential by preventing aggregation for a single
 * block on the common dimension. However, in such a case we would never pick cpmm because
 * this would result in a degree of parallelism of 1.
 * 
 */
public class CpmmSPInstruction extends BinarySPInstruction {
	private final boolean _outputEmptyBlocks;
	private final SparkAggType _aggtype;
	
	private CpmmSPInstruction(Operator op, CPOperand in1, CPOperand in2, CPOperand out, boolean outputEmptyBlocks, SparkAggType aggtype, String opcode, String istr) {
		super(SPType.CPMM, op, in1, in2, out, opcode, istr);
		_outputEmptyBlocks = outputEmptyBlocks;
		_aggtype = aggtype;
	}

	public static CpmmSPInstruction parseInstruction( String str ) {
		String[] parts = InstructionUtils.getInstructionPartsWithValueType(str);
		String opcode = parts[0];
		if ( !opcode.equalsIgnoreCase("cpmm"))
			throw new DMLRuntimeException("CpmmSPInstruction.parseInstruction(): Unknown opcode " + opcode);
		CPOperand in1 = new CPOperand(parts[1]);
		CPOperand in2 = new CPOperand(parts[2]);
		CPOperand out = new CPOperand(parts[3]);
		AggregateOperator agg = new AggregateOperator(0, Plus.getPlusFnObject());
		AggregateBinaryOperator aggbin = new AggregateBinaryOperator(Multiply.getMultiplyFnObject(), agg);
		boolean outputEmptyBlocks = Boolean.parseBoolean(parts[4]);
		SparkAggType aggtype = SparkAggType.valueOf(parts[5]);
		return new CpmmSPInstruction(aggbin, in1, in2, out, outputEmptyBlocks, aggtype, opcode, str);
	}
	
	@Override
	public void processInstruction(ExecutionContext ec) {
		SparkExecutionContext sec = (SparkExecutionContext)ec;
		
		//get rdd inputs
		JavaPairRDD<MatrixIndexes,MatrixBlock> in1 = sec.getBinaryBlockRDDHandleForVariable(input1.getName());
		JavaPairRDD<MatrixIndexes,MatrixBlock> in2 = sec.getBinaryBlockRDDHandleForVariable(input2.getName());
		MatrixCharacteristics mc1 = sec.getMatrixCharacteristics(input1.getName());
		MatrixCharacteristics mc2 = sec.getMatrixCharacteristics(input2.getName());
		
		if( !_outputEmptyBlocks || _aggtype == SparkAggType.SINGLE_BLOCK ) {
			//prune empty blocks of ultra-sparse matrices
			in1 = in1.filter(new FilterNonEmptyBlocksFunction());
			in2 = in2.filter(new FilterNonEmptyBlocksFunction());
		}
		
		if( SparkUtils.isHashPartitioned(in1) //ZIPMM-like CPMM
			&& mc1.getNumRowBlocks()==1 && mc2.getCols()==1 ) {
			//note: if the major input is hash-partitioned and it's a matrix-vector
			//multiply, avoid the index mapping to preserve the partitioning similar
			//to a ZIPMM but with different transpose characteristics
			JavaRDD<MatrixBlock> out = in1
				.join(in2.mapToPair(new ReorgMapFunction("r'")))
				.values().map(new Cpmm2MultiplyFunction())
				.filter(new FilterNonEmptyBlocksFunction2());
			MatrixBlock out2 = RDDAggregateUtils.sumStable(out);
			
			//put output block into symbol table (no lineage because single block)
			//this also includes implicit maintenance of matrix characteristics
			sec.setMatrixOutput(output.getName(), out2);
		}
		else //GENERAL CPMM
		{
			//compute preferred join degree of parallelism
			int numPreferred = getPreferredParJoin(mc1, mc2, in1.getNumPartitions(), in2.getNumPartitions());
			int numPartJoin = Math.min(getMaxParJoin(mc1, mc2), numPreferred);
			
			//process core cpmm matrix multiply 
			JavaPairRDD<Long, IndexedMatrixValue> tmp1 = in1.mapToPair(new CpmmIndexFunction(true));
			JavaPairRDD<Long, IndexedMatrixValue> tmp2 = in2.mapToPair(new CpmmIndexFunction(false));
			JavaPairRDD<MatrixIndexes,MatrixBlock> out = tmp1
				.join(tmp2, numPartJoin)                // join over common dimension
				.mapToPair(new CpmmMultiplyFunction()); // compute block multiplications
			
			//process cpmm aggregation and handle outputs
			if( _aggtype == SparkAggType.SINGLE_BLOCK ) {
				//prune empty blocks and aggregate all results
				out = out.filter(new FilterNonEmptyBlocksFunction());
				MatrixBlock out2 = RDDAggregateUtils.sumStable(out);
				
				//put output block into symbol table (no lineage because single block)
				//this also includes implicit maintenance of matrix characteristics
				sec.setMatrixOutput(output.getName(), out2);
			}
			else { //DEFAULT: MULTI_BLOCK
				if( !_outputEmptyBlocks )
					out = out.filter(new FilterNonEmptyBlocksFunction());
				out = RDDAggregateUtils.sumByKeyStable(out, false);
				
				//put output RDD handle into symbol table
				sec.setRDDHandleForVariable(output.getName(), out);
				sec.addLineageRDD(output.getName(), input1.getName());
				sec.addLineageRDD(output.getName(), input2.getName());
				
				//update output statistics if not inferred
				updateBinaryMMOutputMatrixCharacteristics(sec, true);
			}
		}
	}
	
	private static int getPreferredParJoin(MatrixCharacteristics mc1, MatrixCharacteristics mc2, int numPar1, int numPar2) {
		int defPar = SparkExecutionContext.getDefaultParallelism(true);
		int maxParIn = Math.max(numPar1, numPar2);
		int maxSizeIn = SparkUtils.getNumPreferredPartitions(mc1) +
			SparkUtils.getNumPreferredPartitions(mc2);
		int tmp = (mc1.dimsKnown(true) && mc2.dimsKnown(true)) ? 
			Math.max(maxSizeIn, maxParIn) : maxParIn;
		return (tmp > defPar/2) ? Math.max(tmp, defPar) : tmp;
	}
	
	private static int getMaxParJoin(MatrixCharacteristics mc1, MatrixCharacteristics mc2) {
		return mc1.colsKnown() ? (int)mc1.getNumColBlocks() :
			mc2.rowsKnown() ? (int)mc2.getNumRowBlocks() :
			Integer.MAX_VALUE;
	}

	private static class CpmmIndexFunction implements PairFunction<Tuple2<MatrixIndexes, MatrixBlock>, Long, IndexedMatrixValue>
	{
		private static final long serialVersionUID = -1187183128301671162L;
		private final boolean _left;
		
		public CpmmIndexFunction( boolean left ) {
			_left = left;
		}
		
		@Override
		public Tuple2<Long, IndexedMatrixValue> call(Tuple2<MatrixIndexes, MatrixBlock> arg0) throws Exception {
			IndexedMatrixValue value = new IndexedMatrixValue(arg0._1(), arg0._2());
			Long key = _left ? arg0._1.getColumnIndex() : arg0._1.getRowIndex();
			return new Tuple2<>(key, value);
		}
	}

	private static class CpmmMultiplyFunction implements PairFunction<Tuple2<Long, Tuple2<IndexedMatrixValue,IndexedMatrixValue>>, MatrixIndexes, MatrixBlock>
	{
		private static final long serialVersionUID = -2009255629093036642L;
		private AggregateBinaryOperator _op = null;

		@Override
		public Tuple2<MatrixIndexes, MatrixBlock> call(Tuple2<Long, Tuple2<IndexedMatrixValue, IndexedMatrixValue>> arg0)
			throws Exception
		{
			if( _op == null ) { //lazy operator construction
				AggregateOperator agg = new AggregateOperator(0, Plus.getPlusFnObject());
				_op = new AggregateBinaryOperator(Multiply.getMultiplyFnObject(), agg);
			}
			
			MatrixBlock blkIn1 = (MatrixBlock)arg0._2()._1().getValue();
			MatrixBlock blkIn2 = (MatrixBlock)arg0._2()._2().getValue();
			MatrixIndexes ixOut = new MatrixIndexes();
			
			//core block matrix multiplication 
			MatrixBlock blkOut = OperationsOnMatrixValues
				.matMult(blkIn1, blkIn2, new MatrixBlock(), _op);
			
			//return target block
			ixOut.setIndexes(arg0._2()._1().getIndexes().getRowIndex(),
				arg0._2()._2().getIndexes().getColumnIndex());
			return new Tuple2<>( ixOut, blkOut );
		}
	}
	
	private static class Cpmm2MultiplyFunction implements Function<Tuple2<MatrixBlock,MatrixBlock>, MatrixBlock>
	{
		private static final long serialVersionUID = -3718880362385713416L;
		private AggregateBinaryOperator _op = null;
		private ReorgOperator _rop = null;
		
		@Override
		public MatrixBlock call(Tuple2<MatrixBlock, MatrixBlock> arg0) throws Exception {
			 //lazy operator construction
			if( _op == null ) {
				AggregateOperator agg = new AggregateOperator(0, Plus.getPlusFnObject());
				_op = new AggregateBinaryOperator(Multiply.getMultiplyFnObject(), agg);
				_rop = new ReorgOperator(SwapIndex.getSwapIndexFnObject());
			}
			//prepare inputs, including transpose of right-hand-side
			MatrixBlock in1 = arg0._1();
			MatrixBlock in2 = (MatrixBlock)arg0._2()
				.reorgOperations(_rop, new MatrixBlock(), 0, 0, 0);
			//core block matrix multiplication
			return OperationsOnMatrixValues
				.matMult(in1, in2, new MatrixBlock(), _op);
		}
	}
}
