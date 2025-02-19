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

import java.util.Iterator;
import java.util.List;

import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.function.PairFlatMapFunction;
import org.tugraz.sysds.common.Types.ValueType;
import org.tugraz.sysds.runtime.DMLRuntimeException;
import org.tugraz.sysds.runtime.controlprogram.context.ExecutionContext;
import org.tugraz.sysds.runtime.controlprogram.context.SparkExecutionContext;
import org.tugraz.sysds.runtime.instructions.InstructionUtils;
import org.tugraz.sysds.runtime.instructions.cp.CPOperand;
import org.tugraz.sysds.runtime.instructions.spark.functions.FilterNonEmptyBlocksFunction;
import org.tugraz.sysds.runtime.instructions.spark.utils.RDDAggregateUtils;
import org.tugraz.sysds.runtime.instructions.spark.utils.SparkUtils;
import org.tugraz.sysds.runtime.matrix.data.LibMatrixReorg;
import org.tugraz.sysds.runtime.matrix.data.MatrixBlock;
import org.tugraz.sysds.runtime.matrix.data.MatrixIndexes;
import org.tugraz.sysds.runtime.matrix.mapred.IndexedMatrixValue;
import org.tugraz.sysds.runtime.matrix.operators.Operator;
import org.tugraz.sysds.runtime.meta.MatrixCharacteristics;

import scala.Tuple2;

public class MatrixReshapeSPInstruction extends UnarySPInstruction
{
	private final CPOperand _opRows;
	private final CPOperand _opCols;
	private final CPOperand _opByRow;
	private final boolean _outputEmptyBlocks;

	private MatrixReshapeSPInstruction(Operator op, CPOperand in1, CPOperand in2, CPOperand in3, CPOperand in4,
			CPOperand out, boolean outputEmptyBlocks, String opcode, String istr) {
		super(SPType.MatrixReshape, op, in1, out, opcode, istr);
		_opRows = in2;
		_opCols = in3;
		_opByRow = in4;
		_outputEmptyBlocks = outputEmptyBlocks;
	}

	public static MatrixReshapeSPInstruction parseInstruction ( String str ) {
		String[] parts = InstructionUtils.getInstructionPartsWithValueType(str);
		InstructionUtils.checkNumFields( parts, 7 );
		
		String opcode = parts[0];
		CPOperand in1 = new CPOperand(parts[1]);
		CPOperand rows = new CPOperand(parts[2]);
		CPOperand cols = new CPOperand(parts[3]);
		//TODO handle dims for tensors parts[4]
		CPOperand byRow = new CPOperand(parts[5]);
		CPOperand out = new CPOperand(parts[6]);
		boolean outputEmptyBlocks = Boolean.parseBoolean(parts[7]);
		 
		if(!opcode.equalsIgnoreCase("rshape"))
			throw new DMLRuntimeException("Unknown opcode while parsing an MatrixReshapeInstruction: " + str);
		else
			return new MatrixReshapeSPInstruction(new Operator(true), in1, rows, cols, byRow, out, outputEmptyBlocks, opcode, str);
	}
	
	@Override
	public void processInstruction(ExecutionContext ec) {
		SparkExecutionContext sec = (SparkExecutionContext)ec;
		
		//get parameters
		long rows = ec.getScalarInput(_opRows).getLongValue(); //save cast
		long cols = ec.getScalarInput(_opCols).getLongValue(); //save cast
		boolean byRow = ec.getScalarInput(_opByRow.getName(), ValueType.BOOLEAN, _opByRow.isLiteral()).getBooleanValue();
		
		//get inputs 
		JavaPairRDD<MatrixIndexes,MatrixBlock> in1 = sec
			.getBinaryBlockRDDHandleForVariable(input1.getName(), -1, _outputEmptyBlocks);
		MatrixCharacteristics mcIn = sec.getMatrixCharacteristics( input1.getName() );
		MatrixCharacteristics mcOut = sec.getMatrixCharacteristics( output.getName() );
		
		//update output characteristics and sanity check
		mcOut.set(rows, cols, mcIn.getRowsPerBlock(), mcIn.getColsPerBlock(), mcIn.getNonZeros());
		if( !mcIn.nnzKnown() )
			mcOut.setNonZerosBound(mcIn.getNonZerosBound());
		if( mcIn.getRows()*mcIn.getCols() != mcOut.getRows()*mcOut.getCols() ) {
			throw new DMLRuntimeException("Incompatible matrix characteristics for reshape: "
				+ mcIn.getRows()+"x"+mcIn.getCols()+" vs "+mcOut.getRows()+"x"+mcOut.getCols());
		}
		
		if( !_outputEmptyBlocks )
			in1 = in1.filter(new FilterNonEmptyBlocksFunction());
		
		//execute reshape instruction
		JavaPairRDD<MatrixIndexes,MatrixBlock> out = 
			in1.flatMapToPair(new RDDReshapeFunction(mcIn, mcOut, byRow, _outputEmptyBlocks));
		out = RDDAggregateUtils.mergeByKey(out);
		
		//put output RDD handle into symbol table
		sec.setRDDHandleForVariable(output.getName(), out);
		sec.addLineageRDD(output.getName(), input1.getName());
	}
	
	private static class RDDReshapeFunction implements PairFlatMapFunction<Tuple2<MatrixIndexes, MatrixBlock>, MatrixIndexes, MatrixBlock> 
	{
		private static final long serialVersionUID = 2819309412002224478L;
		
		private final MatrixCharacteristics _mcIn;
		private final MatrixCharacteristics _mcOut;
		private final boolean _byrow;
		private final boolean _outputEmptyBlocks;
		
		public RDDReshapeFunction( MatrixCharacteristics mcIn, MatrixCharacteristics mcOut, boolean byrow, boolean outputEmptyBlocks) {
			_mcIn = mcIn;
			_mcOut = mcOut;
			_byrow = byrow;
			_outputEmptyBlocks = outputEmptyBlocks;
		}
		
		@Override
		public Iterator<Tuple2<MatrixIndexes, MatrixBlock>> call( Tuple2<MatrixIndexes, MatrixBlock> arg0 ) 
			throws Exception 
		{
			//input conversion (for libmatrixreorg compatibility)
			IndexedMatrixValue in = SparkUtils.toIndexedMatrixBlock(arg0);
			
			//execute actual reshape operation
			List<IndexedMatrixValue> out = LibMatrixReorg
				.reshape(in, _mcIn, _mcOut, _byrow, _outputEmptyBlocks);

			//output conversion (for compatibility w/ rdd schema)
			return SparkUtils.fromIndexedMatrixBlock(out).iterator();
		}
	}
}
