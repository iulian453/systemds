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

package org.tugraz.sysds.hops;

import org.tugraz.sysds.api.DMLScript;
import org.tugraz.sysds.conf.ConfigurationManager;
import org.tugraz.sysds.hops.rewrite.HopRewriteUtils;
import org.tugraz.sysds.lops.Append;
import org.tugraz.sysds.lops.AppendG;
import org.tugraz.sysds.lops.AppendGAlignedSP;
import org.tugraz.sysds.lops.AppendM;
import org.tugraz.sysds.lops.AppendR;
import org.tugraz.sysds.lops.Binary;
import org.tugraz.sysds.lops.BinaryM;
import org.tugraz.sysds.lops.BinaryScalar;
import org.tugraz.sysds.lops.BinaryUAggChain;
import org.tugraz.sysds.lops.CentralMoment;
import org.tugraz.sysds.lops.CoVariance;
import org.tugraz.sysds.lops.Data;
import org.tugraz.sysds.lops.DnnTransform;
import org.tugraz.sysds.lops.Lop;
import org.tugraz.sysds.lops.PickByCount;
import org.tugraz.sysds.lops.SortKeys;
import org.tugraz.sysds.lops.Unary;
import org.tugraz.sysds.lops.UnaryCP;
import org.tugraz.sysds.lops.LopProperties.ExecType;
import org.tugraz.sysds.runtime.meta.MatrixCharacteristics;
import org.tugraz.sysds.common.Types.DataType;
import org.tugraz.sysds.common.Types.ValueType;


/* Binary (cell operations): aij + bij
 * 		Properties: 
 * 			Symbol: *, -, +, ...
 * 			2 Operands
 * 		Semantic: align indices (sort), then perform operation
 */

public class BinaryOp extends MultiThreadedHop
{
	//we use the full remote memory budget (but reduced by sort buffer), 
	public static final double APPEND_MEM_MULTIPLIER = 1.0;
	
	private Hop.OpOp2 op;
	private boolean outer = false;
	
	public static AppendMethod FORCED_APPEND_METHOD = null;
	
	public enum AppendMethod { 
		CP_APPEND, //in-memory general case append (implicitly selected for CP)
		MR_MAPPEND, //map-only append (rhs must be vector and fit in mapper mem)
		MR_RAPPEND, //reduce-only append (output must have at most one column block)
		MR_GAPPEND, //map-reduce general case append (map-extend, aggregate)
		SP_GAlignedAppend // special case for general case in Spark where left.getCols() % left.getColsPerBlock() == 0
	}
	
	private enum MMBinaryMethod {
		CP_BINARY, //(implicitly selected for CP) 
		MR_BINARY_R, //both mm, mv 
		MR_BINARY_M, //only mv (mr/spark)
		MR_BINARY_OUTER_M,
		MR_BINARY_OUTER_R, //only vv 
		MR_BINARY_UAGG_CHAIN, //(mr/spark)
	}
	
	private BinaryOp() {
		//default constructor for clone
	}
	
	public BinaryOp(String l, DataType dt, ValueType vt, Hop.OpOp2 o,
			Hop inp1, Hop inp2) {
		super(l, dt, vt);
		op = o;
		getInput().add(0, inp1);
		getInput().add(1, inp2);

		inp1.getParent().add(this);
		inp2.getParent().add(this);
		
		//compute unknown dims and nnz
		refreshSizeInformation();
	}

	@Override
	public void checkArity() {
		HopsException.check(_input.size() == 2, this, "should have arity 2 but has arity %d", _input.size());
	}

	public OpOp2 getOp() {
		return op;
	}
	
	public void setOp(OpOp2 iop) {
		 op = iop;
	}
	
	public void setOuterVectorOperation(boolean flag) {
		outer = flag;
	}
	
	public boolean isOuter(){
		return outer;
	}
	
	@Override
	public boolean isGPUEnabled() {
		if(!DMLScript.USE_ACCELERATOR)
			return false;
		
		switch(op) 
		{
			case IQM:
			case MOMENT:
			case COV:
			case QUANTILE:
			case INTERQUANTILE:
			case MEDIAN:
				return false;
			case CBIND: 
			case RBIND: {
				DataType dt1 = getInput().get(0).getDataType();
				return dt1 == DataType.MATRIX; // only matrix cbind, rbind supported on GPU
			}
			default: {
				DataType dt1 = getInput().get(0).getDataType();
				DataType dt2 = getInput().get(1).getDataType();
				
				boolean isMatrixScalar = (dt1 == DataType.MATRIX && dt2 == DataType.SCALAR) || (dt1 == DataType.SCALAR && dt2 == DataType.MATRIX);
				boolean isMatrixMatrix = (dt1 == DataType.MATRIX && dt2 == DataType.MATRIX);
				
				OpOp2 [] supportedOps = { OpOp2.MULT, OpOp2.PLUS, OpOp2.MINUS, OpOp2.DIV, OpOp2.POW, OpOp2.MINUS1_MULT, 
						OpOp2.MODULUS, OpOp2.INTDIV, OpOp2.LESS, OpOp2.LESSEQUAL, OpOp2.EQUAL, OpOp2.NOTEQUAL, OpOp2.GREATER, OpOp2.GREATEREQUAL};
			
				if(isMatrixScalar && (op == OpOp2.MINUS_NZ || op == OpOp2.MIN || op == OpOp2.MAX)) {
					// Only supported for matrix scalar:
					return true;
				}
				else if(isMatrixMatrix && op == OpOp2.SOLVE) {
					// Only supported for matrix matrix:
					return true;
				}
				else if(isMatrixScalar || isMatrixMatrix) {
					for(OpOp2 supportedOp : supportedOps) {
						if(op == supportedOp)
							return true;
					}
					return false;
				}
				else
					return false;
			}
		}
	}
	
	@Override
	public Lop constructLops() 
	{
		//return already created lops
		if( getLops() != null )
			return getLops();

		//select the execution type
		ExecType et = optFindExecType();
		
		switch(op) 
		{
			case IQM: {
				constructLopsIQM(et);
				break;
			}
			case MOMENT: {
				constructLopsCentralMoment(et);
				break;
			}	
			case COV: {
				constructLopsCovariance(et);
				break;
			}
			case QUANTILE:
			case INTERQUANTILE: {
				constructLopsQuantile(et);
				break;
			}
			case MEDIAN: {
				constructLopsMedian(et);
				break;
			}
			case CBIND: 
			case RBIND: {
				constructLopsAppend(et);
				break;
			}
			default:
				constructLopsBinaryDefault();
		}

		//add reblock/checkpoint lops if necessary
		constructAndSetLopsDataFlowProperties();
		
		return getLops();
	}
	
	private void constructLopsIQM(ExecType et) {
		SortKeys sort = SortKeys.constructSortByValueLop(
				getInput().get(0).constructLops(), 
				getInput().get(1).constructLops(), 
				SortKeys.OperationTypes.WithWeights, 
				getInput().get(0).getDataType(), getInput().get(0).getValueType(), et);
		sort.getOutputParameters().setDimensions(
				getInput().get(0).getDim1(),
				getInput().get(0).getDim2(), 
				getInput().get(0).getRowsInBlock(), 
				getInput().get(0).getColsInBlock(), 
				getInput().get(0).getNnz());
		PickByCount pick = new PickByCount(
				sort,
				null,
				getDataType(),
				getValueType(),
				PickByCount.OperationTypes.IQM, et, true);
		
		setOutputDimensions(pick);
		setLineNumbers(pick);
		setLops(pick);
	}
	
	private void constructLopsMedian(ExecType et) {
		SortKeys sort = SortKeys.constructSortByValueLop(
				getInput().get(0).constructLops(), 
				getInput().get(1).constructLops(), 
				SortKeys.OperationTypes.WithWeights, 
				getInput().get(0).getDataType(), getInput().get(0).getValueType(), et);
		sort.getOutputParameters().setDimensions(
				getInput().get(0).getDim1(),
				getInput().get(0).getDim2(),
				getInput().get(0).getRowsInBlock(),
				getInput().get(0).getColsInBlock(), 
				getInput().get(0).getNnz());
		PickByCount pick = new PickByCount(
				sort,
				Data.createLiteralLop(ValueType.FP64, Double.toString(0.5)),
				getDataType(),
				getValueType(),
				PickByCount.OperationTypes.MEDIAN, et, true);

		pick.getOutputParameters().setDimensions(getDim1(),
				getDim2(), getRowsInBlock(), getColsInBlock(), getNnz());
		
		pick.setAllPositions(this.getFilename(), this.getBeginLine(), this.getBeginColumn(), this.getEndLine(), this.getEndColumn());

		setLops(pick);
	}
	
	private void constructLopsCentralMoment(ExecType et) 
	{
		// The output data type is a SCALAR if central moment 
		// gets computed in CP/SPARK, and it will be MATRIX otherwise.
		DataType dt = DataType.SCALAR;
		CentralMoment cm = new CentralMoment(
				getInput().get(0).constructLops(), 
				getInput().get(1).constructLops(),
				dt, getValueType(), et);

		setLineNumbers(cm);
		cm.getOutputParameters().setDimensions(0, 0, 0, 0, -1);
		setLops(cm);
	}

	private void constructLopsCovariance(ExecType et) {
		CoVariance cov = new CoVariance(
				getInput().get(0).constructLops(), 
				getInput().get(1).constructLops(), 
				getDataType(), getValueType(), et);
		cov.getOutputParameters().setDimensions(0, 0, 0, 0, -1);
		setLineNumbers(cov);
		setLops(cov);
	}
	
	private void constructLopsQuantile(ExecType et) {
		// 1st arguments needs to be a 1-dimensional matrix
		// For QUANTILE: 2nd argument is scalar or 1-dimensional matrix
		// For INTERQUANTILE: 2nd argument is always a scalar

		PickByCount.OperationTypes pick_op = null;
		if(op == Hop.OpOp2.QUANTILE)
			pick_op = PickByCount.OperationTypes.VALUEPICK;
		else
			pick_op = PickByCount.OperationTypes.RANGEPICK;

		SortKeys sort = SortKeys.constructSortByValueLop(
							getInput().get(0).constructLops(), 
							SortKeys.OperationTypes.WithoutWeights, 
							DataType.MATRIX, ValueType.FP64, et );
		sort.getOutputParameters().setDimensions(
				getInput().get(0).getDim1(),
				getInput().get(0).getDim2(),
				getInput().get(0).getRowsInBlock(),
				getInput().get(0).getColsInBlock(), 
				getInput().get(0).getNnz());
		PickByCount pick = new PickByCount( sort, getInput().get(1).constructLops(),
				getDataType(), getValueType(), pick_op, et, true);

		setOutputDimensions(pick);
		setLineNumbers(pick);
		setLops(pick);
	}

	private void constructLopsAppend(ExecType et) 
	{
		DataType dt1 = getInput().get(0).getDataType();
		DataType dt2 = getInput().get(1).getDataType();
		ValueType vt1 = getInput().get(0).getValueType();
		ValueType vt2 = getInput().get(1).getValueType();
		boolean cbind = op==OpOp2.CBIND;
		
		//sanity check for input data types
		if( !((dt1==DataType.MATRIX && dt2==DataType.MATRIX)
			 ||(dt1==DataType.FRAME && dt2==DataType.FRAME) 	
			 ||(dt1==DataType.SCALAR && dt2==DataType.SCALAR
			   && vt1==ValueType.STRING && vt2==ValueType.STRING )) )
		{
			throw new HopsException("Append can only apply to two matrices, two frames, or two scalar strings!");
		}
				
		Lop append = null;
		if( dt1==DataType.MATRIX || dt1==DataType.FRAME )
		{
			long rlen = cbind ? getInput().get(0).getDim1() : (getInput().get(0).dimsKnown() && getInput().get(1).dimsKnown()) ?
				getInput().get(0).getDim1()+getInput().get(1).getDim1() : -1;
			long clen = cbind ? ((getInput().get(0).dimsKnown() && getInput().get(1).dimsKnown()) ?
				getInput().get(0).getDim2()+getInput().get(1).getDim2() : -1) : getInput().get(0).getDim2();			
		
			if(et == ExecType.SPARK) 
			{
				append = constructSPAppendLop(getInput().get(0), getInput().get(1), getDataType(), getValueType(), cbind, this);
				append.getOutputParameters().setDimensions(rlen, clen, getRowsInBlock(), getColsInBlock(), getNnz());
			}
			else //CP
			{
				Lop offset = createOffsetLop( getInput().get(0), cbind ); //offset 1st input
				append = new Append(getInput().get(0).constructLops(), getInput().get(1).constructLops(), offset, getDataType(), getValueType(), cbind, et);
				append.getOutputParameters().setDimensions(rlen, clen, getRowsInBlock(), getColsInBlock(), getNnz());
			}
		}
		else //SCALAR-STRING and SCALAR-STRING (always CP)
		{
			append = new Append(getInput().get(0).constructLops(), getInput().get(1).constructLops(),
				     Data.createLiteralLop(ValueType.INT64, "-1"), getDataType(), getValueType(), cbind, ExecType.CP);
			append.getOutputParameters().setDimensions(0,0,-1,-1,-1);
		}
		
		setLineNumbers(append);
		setLops(append);
	}

	private void constructLopsBinaryDefault() 
	{
		/* Default behavior for BinaryOp */
		// it depends on input data types
		DataType dt1 = getInput().get(0).getDataType();
		DataType dt2 = getInput().get(1).getDataType();
		
		if (dt1 == dt2 && dt1 == DataType.SCALAR) {
			// Both operands scalar
			BinaryScalar binScalar1 = new BinaryScalar(getInput().get(0)
				.constructLops(),getInput().get(1).constructLops(),
				HopsOpOp2LopsBS.get(op), getDataType(), getValueType());
			binScalar1.getOutputParameters().setDimensions(0, 0, 0, 0, -1);
			setLineNumbers(binScalar1);
			setLops(binScalar1);

		} 
		else if ((dt1 == DataType.MATRIX && dt2 == DataType.SCALAR)
				   || (dt1 == DataType.SCALAR && dt2 == DataType.MATRIX)) {

			// One operand is Matrix and the other is scalar
			ExecType et = optFindExecType();
			
			//select specific operator implementations
			Unary.OperationTypes ot = null;
			Hop right = getInput().get(1);
			if( op==OpOp2.POW && right instanceof LiteralOp && ((LiteralOp)right).getDoubleValue()==2.0  )
				ot = Unary.OperationTypes.POW2;
			else if( op==OpOp2.MULT && right instanceof LiteralOp && ((LiteralOp)right).getDoubleValue()==2.0  )
				ot = Unary.OperationTypes.MULTIPLY2;
			else //general case
				ot = HopsOpOp2LopsU.get(op);

			Unary unary1 = new Unary(getInput().get(0).constructLops(),
				getInput().get(1).constructLops(), ot, getDataType(), getValueType(), et);
		
			setOutputDimensions(unary1);
			setLineNumbers(unary1);
			setLops(unary1);
		} 
		else 
		{
			// Both operands are Matrixes
			ExecType et = optFindExecType();
			boolean isGPUSoftmax = et == ExecType.GPU && op == Hop.OpOp2.DIV && 
					getInput().get(0) instanceof UnaryOp && getInput().get(1) instanceof AggUnaryOp && 
					((UnaryOp)getInput().get(0)).getOp() == OpOp1.EXP && ((AggUnaryOp)getInput().get(1)).getOp() == AggOp.SUM &&
					((AggUnaryOp)getInput().get(1)).getDirection() == Direction.Row &&
					getInput().get(0) == getInput().get(1).getInput().get(0);
			if(isGPUSoftmax) {
				UnaryCP softmax = new UnaryCP(getInput().get(0).getInput().get(0).constructLops(), UnaryCP.OperationTypes.SOFTMAX, 
						getDataType(), getValueType(), et);
				setOutputDimensions(softmax);
				setLineNumbers(softmax);
				setLops(softmax);
			}
			else if ( et == ExecType.CP || et == ExecType.GPU ) 
			{
				Lop binary = null;
				
				boolean isLeftXGt = (getInput().get(0) instanceof BinaryOp) && ((BinaryOp) getInput().get(0)).getOp() == OpOp2.GREATER;
				Hop potentialZero = isLeftXGt ? ((BinaryOp) getInput().get(0)).getInput().get(1) : null;
				
				boolean isLeftXGt0 = isLeftXGt && potentialZero != null
					&& HopRewriteUtils.isLiteralOfValue(potentialZero, 0);
				
				if(op == OpOp2.MULT && isLeftXGt0 && 
					!getInput().get(0).isVector() && !getInput().get(1).isVector()
					&& getInput().get(0).dimsKnown() && getInput().get(1).dimsKnown()) {
					binary = new DnnTransform(getInput().get(0).getInput().get(0).constructLops(), 
						getInput().get(1).constructLops(), DnnTransform.OperationTypes.RELU_BACKWARD,
						getDataType(), getValueType(), et, OptimizerUtils.getConstrainedNumThreads(_maxNumThreads));
				}
				else
					binary = new Binary(getInput().get(0).constructLops(), getInput().get(1).constructLops(), HopsOpOp2LopsB.get(op),
						getDataType(), getValueType(), et);
				
				setOutputDimensions(binary);
				setLineNumbers(binary);
				setLops(binary);
			}
			else if(et == ExecType.SPARK)
			{
				Hop left = getInput().get(0);
				Hop right = getInput().get(1);
				MMBinaryMethod mbin = optFindMMBinaryMethodSpark(left, right);
				
				Lop  binary = null;
				if( mbin == MMBinaryMethod.MR_BINARY_UAGG_CHAIN ) {
					AggUnaryOp uRight = (AggUnaryOp)right;
					binary = new BinaryUAggChain(left.constructLops(), HopsOpOp2LopsB.get(op),
							HopsAgg2Lops.get(uRight.getOp()), HopsDirection2Lops.get(uRight.getDirection()),
							getDataType(), getValueType(), et);
				}
				else if (mbin == MMBinaryMethod.MR_BINARY_M) {
					boolean partitioned = false;
					boolean isColVector = (right.getDim2()==1 && left.getDim1()==right.getDim1());
					
					binary = new BinaryM(left.constructLops(), right.constructLops(),
							HopsOpOp2LopsB.get(op), getDataType(), getValueType(), et, partitioned, isColVector); 
				}
				else {
					binary = new Binary(left.constructLops(), right.constructLops(), 
							HopsOpOp2LopsB.get(op), getDataType(), getValueType(), et);
				}
				
				setOutputDimensions(binary);
				setLineNumbers(binary);
				setLops(binary);
			}
		}
	}

	@Override
	public String getOpString() {
		String s = new String("");
		s += "b(" + HopsOpOp2String.get(op) + ")";
		return s;
	}

	@Override
	protected double computeOutputMemEstimate( long dim1, long dim2, long nnz )
	{		
		double ret = 0;
		
		//preprocessing step (recognize unknowns)
		if( dimsKnown() && _nnz<0 ) //never after inference
			nnz = -1; 
		
		if((op==OpOp2.CBIND || op==OpOp2.RBIND) && !ConfigurationManager.isDynamicRecompilation() && !(getDataType()==DataType.SCALAR) ) {	
			ret = OptimizerUtils.DEFAULT_SIZE;
		}
		else
		{
			double sparsity = 1.0;
			if( nnz < 0 ){ //check for exactly known nnz
				Hop input1 = getInput().get(0);
				Hop input2 = getInput().get(1);
				if( input1.dimsKnown() && input2.dimsKnown() )
				{
					if( OptimizerUtils.isBinaryOpConditionalSparseSafe(op) && input2 instanceof LiteralOp ) {
						double sp1 = (input1.getNnz()>0 && input1.getDataType()==DataType.MATRIX) ? OptimizerUtils.getSparsity(input1.getDim1(), input1.getDim2(), input1.getNnz()) : 1.0;
						LiteralOp lit = (LiteralOp)input2;
						sparsity = OptimizerUtils.getBinaryOpSparsityConditionalSparseSafe(sp1, op, lit);
					}
					else {
						double sp1 = (input1.getNnz()>0 && input1.getDataType()==DataType.MATRIX) ? OptimizerUtils.getSparsity(input1.getDim1(), input1.getDim2(), input1.getNnz()) : 1.0;
						double sp2 = (input2.getNnz()>0 && input2.getDataType()==DataType.MATRIX) ? OptimizerUtils.getSparsity(input2.getDim1(), input2.getDim2(), input2.getNnz()) : 1.0;
						//sparsity estimates are conservative in terms of the worstcase behavior, however,
						//for outer vector operations the average case is equivalent to the worst case.
						sparsity = OptimizerUtils.getBinaryOpSparsity(sp1, sp2, op, !outer);
					}
				}
			}
			else //e.g., for append,pow or after inference
				sparsity = OptimizerUtils.getSparsity(dim1, dim2, nnz);
			
			ret = OptimizerUtils.estimateSizeExactSparsity(dim1, dim2, sparsity);	
		}
		
		
		return ret;
	}
	
	@Override
	protected double computeIntermediateMemEstimate( long dim1, long dim2, long nnz )
	{
		double ret = 0;
		if ( op == OpOp2.QUANTILE || op == OpOp2.IQM  || op == OpOp2.MEDIAN ) {
			// buffer (=2*input_size) and output (=input_size) for SORT operation 
			// getMemEstimate works for both cases of known dims and worst-case
			ret = getInput().get(0).getMemEstimate() * 3; 
		}
		else if ( op == OpOp2.SOLVE ) {
			if (isGPUEnabled()) {
				// Solve on the GPU takes an awful lot of intermediate space
				// First the inputs are converted from row-major to column major
				// Then a workspace and a temporary output (workSize, tauSize) are needed
				long m = getInput().get(0).getDim1();
				long n = getInput().get(0).getDim2();
				long tauSize = OptimizerUtils.estimateSize(m, 1);
				long workSize = OptimizerUtils.estimateSize(m, n);
				long AtmpSize = OptimizerUtils.estimateSize(m, n);
				long BtmpSize = OptimizerUtils.estimateSize(n, 1);
				return (tauSize + workSize + AtmpSize + BtmpSize);
			} else {
				// x=solve(A,b) relies on QR decomposition of A, which is done using Apache commons-math
				// matrix of size same as the first input
				double interOutput = OptimizerUtils
						.estimateSizeExactSparsity(getInput().get(0).getDim1(), getInput().get(0).getDim2(), 1.0);
				return interOutput;
			}

		}

		return ret;
	}
	
	@Override
	protected long[] inferOutputCharacteristics( MemoTable memo )
	{
		long[] ret = null;
		
		MatrixCharacteristics[] mc = memo.getAllInputStats(getInput());
		Hop input1 = getInput().get(0);
		Hop input2 = getInput().get(1);		
		DataType dt1 = input1.getDataType();
		DataType dt2 = input2.getDataType();
		
		if( op== OpOp2.CBIND ) {
			long ldim1 = -1, ldim2 = -1, lnnz = -1;
			
			if( mc[0].rowsKnown() || mc[1].rowsKnown() )
				ldim1 = mc[0].rowsKnown() ? mc[0].getRows() : mc[1].getRows();
			if( mc[0].colsKnown() && mc[1].colsKnown() )
				ldim2 = mc[0].getCols()+mc[1].getCols();
			if( mc[0].nnzKnown() && mc[1].nnzKnown() )
				lnnz = mc[0].getNonZeros() + mc[1].getNonZeros();
			
			if( ldim1 >= 0 || ldim2 >= 0 || lnnz >= 0 )
				return new long[]{ldim1, ldim2, lnnz};
		}
		else if( op == OpOp2.RBIND ) {
			long ldim1 = -1, ldim2 = -1, lnnz = -1;
			
			if( mc[0].colsKnown() || mc[1].colsKnown() )
				ldim2 = mc[0].colsKnown() ? mc[0].getCols() : mc[1].getCols();
			if( mc[0].rowsKnown() && mc[1].rowsKnown() )
				ldim1 = mc[0].getRows()+mc[1].getRows();
			if( mc[0].nnzKnown() && mc[1].nnzKnown() )
				lnnz = mc[0].getNonZeros() + mc[1].getNonZeros();
			
			if( ldim1 >= 0 || ldim2 >= 0 || lnnz >= 0 )
				return new long[]{ldim1, ldim2, lnnz};
		}
		else if ( op == OpOp2.SOLVE ) {
			// Output is a (likely to be dense) vector of size number of columns in the first input
			if ( mc[0].getCols() >= 0 ) {
				ret = new long[]{ mc[0].getCols(), 1, mc[0].getCols()};
			}
		}
		else //general case
		{
			long ldim1, ldim2;
			double sp1 = 1.0, sp2 = 1.0;
			
			if( dt1 == DataType.MATRIX && dt2 == DataType.SCALAR && mc[0].dimsKnown() )
			{
				ldim1 = mc[0].getRows();
				ldim2 = mc[0].getCols();
				sp1 = (mc[0].getNonZeros()>0)?OptimizerUtils.getSparsity(ldim1, ldim2, mc[0].getNonZeros()):1.0;	
			}
			else if( dt1 == DataType.SCALAR && dt2 == DataType.MATRIX  ) 
			{
				ldim1 = mc[1].getRows();
				ldim2 = mc[1].getCols();
				sp2 = (mc[1].getNonZeros()>0)?OptimizerUtils.getSparsity(ldim1, ldim2, mc[1].getNonZeros()):1.0;
			}
			else //MATRIX - MATRIX 
			{
				//propagate if either input is known, rows need always be identical,
				//for cols we need to be careful with regard to matrix-vector operations
				if( outer ) //OUTER VECTOR OPERATION
				{
					ldim1 = mc[0].getRows();
					ldim2 = mc[1].getCols();
				}
				else //GENERAL CASE
				{
					ldim1 = (mc[0].rowsKnown()) ? mc[0].getRows() : 
					        (mc[1].getRows()>1) ? mc[1].getRows() : -1;
					ldim2 = (mc[0].colsKnown()) ? mc[0].getCols() : 
						    (mc[1].getCols()>1) ? mc[1].getCols() : -1;
				}
				sp1 = (mc[0].getNonZeros()>0)?OptimizerUtils.getSparsity(ldim1, ldim2, mc[0].getNonZeros()):1.0;
				sp2 = (mc[1].getNonZeros()>0)?OptimizerUtils.getSparsity(ldim1, ldim2, mc[1].getNonZeros()):1.0;
			}
			
			if( ldim1>=0 && ldim2>=0 )
			{
				if( OptimizerUtils.isBinaryOpConditionalSparseSafe(op) && input2 instanceof LiteralOp ) {
					long lnnz = (long) (ldim1*ldim2*OptimizerUtils.getBinaryOpSparsityConditionalSparseSafe(sp1, op,(LiteralOp)input2));
					ret = new long[]{ldim1, ldim2, lnnz};
				}
				else
				{
					//sparsity estimates are conservative in terms of the worstcase behavior, however,
					//for outer vector operations the average case is equivalent to the worst case.
					long lnnz = (long) (ldim1*ldim2*OptimizerUtils.getBinaryOpSparsity(sp1, sp2, op, !outer));
					ret = new long[]{ldim1, ldim2, lnnz};
				}
			}
		}

		return ret;
	}

	@Override
	public boolean allowsAllExecTypes()
	{
		return true;
	}
	
	@Override
	protected ExecType optFindExecType() {
		
		checkAndSetForcedPlatform();
		
		DataType dt1 = getInput().get(0).getDataType();
		DataType dt2 = getInput().get(1).getDataType();
		
		if( _etypeForced != null ) {		
			_etype = _etypeForced;
		}
		else 
		{
			if ( OptimizerUtils.isMemoryBasedOptLevel() ) 
			{
				_etype = findExecTypeByMemEstimate();
			}
			else
			{
				_etype = null;
				if ( dt1 == DataType.MATRIX && dt2 == DataType.MATRIX ) {
					// choose CP if the dimensions of both inputs are below Hops.CPThreshold 
					// OR if both are vectors
					if ( (getInput().get(0).areDimsBelowThreshold() && getInput().get(1).areDimsBelowThreshold())
							|| (getInput().get(0).isVector() && getInput().get(1).isVector()))
					{
						_etype = ExecType.CP;
					}
				}
				else if ( dt1 == DataType.MATRIX && dt2 == DataType.SCALAR ) {
					if ( getInput().get(0).areDimsBelowThreshold() || getInput().get(0).isVector() )
					{
						_etype = ExecType.CP;
					}
				}
				else if ( dt1 == DataType.SCALAR && dt2 == DataType.MATRIX ) {
					if ( getInput().get(1).areDimsBelowThreshold() || getInput().get(1).isVector() )
					{
						_etype = ExecType.CP;
					}
				}
				else
				{
					_etype = ExecType.CP;
				}
				
				//if no CP condition applied
				if( _etype == null )
					_etype = ExecType.SPARK;
			}
		
			//check for valid CP dimensions and matrix size
			checkAndSetInvalidCPDimsAndSize();
		}
			
		//spark-specific decision refinement (execute unary scalar w/ spark input and 
		//single parent also in spark because it's likely cheap and reduces intermediates)
		if( _etype == ExecType.CP && _etypeForced != ExecType.CP
			&& getDataType().isMatrix() && (dt1.isScalar() || dt2.isScalar()) 
			&& supportsMatrixScalarOperations()                          //scalar operations
			&& !(getInput().get(dt1.isScalar()?1:0) instanceof DataOp)   //input is not checkpoint
			&& getInput().get(dt1.isScalar()?1:0).getParent().size()==1  //unary scalar is only parent
			&& !HopRewriteUtils.isSingleBlock(getInput().get(dt1.isScalar()?1:0)) //single block triggered exec
			&& getInput().get(dt1.isScalar()?1:0).optFindExecType() == ExecType.SPARK )					
		{
			//pull unary scalar operation into spark 
			_etype = ExecType.SPARK;
		}

		//mark for recompile (forever)
		setRequiresRecompileIfNecessary();
		
		//ensure cp exec type for single-node operations
		if ( op == OpOp2.SOLVE ) {
			if (isGPUEnabled())
				_etype = ExecType.GPU;
			else
				_etype = ExecType.CP;
		}
		
		return _etype;
	}
	
	public static Lop constructSPAppendLop( Hop left, Hop right, DataType dt, ValueType vt, boolean cbind, Hop current ) 
	{
		Lop ret = null;
		
		Lop offset = createOffsetLop( left, cbind ); //offset 1st input
		AppendMethod am = optFindAppendSPMethod(left.getDim1(), left.getDim2(), right.getDim1(), right.getDim2(), 
				right.getRowsInBlock(), right.getColsInBlock(), right.getNnz(), cbind, dt);
	
		switch( am )
		{
			case MR_MAPPEND: //special case map-only append
			{
				ret = new AppendM(left.constructLops(), right.constructLops(), offset, 
						current.getDataType(), current.getValueType(), cbind, false, ExecType.SPARK);
				break;
			}
			case MR_RAPPEND: //special case reduce append w/ one column block
			{
				ret = new AppendR(left.constructLops(), right.constructLops(), 
						current.getDataType(), current.getValueType(), cbind, ExecType.SPARK);
				break;
			}	
			case MR_GAPPEND:
			{
				Lop offset2 = createOffsetLop( right, cbind ); //offset second input
				ret = new AppendG(left.constructLops(), right.constructLops(), offset, offset2, 
						current.getDataType(), current.getValueType(), cbind, ExecType.SPARK);
				break;
			}
			case SP_GAlignedAppend:
			{
				ret = new AppendGAlignedSP(left.constructLops(), right.constructLops(), offset, 
						current.getDataType(), current.getValueType(), cbind);
				break;
			}
			default:
				throw new HopsException("Invalid SP append method: "+am);
		}
		
		ret.setAllPositions(current.getFilename(), current.getBeginLine(), current.getBeginColumn(), current.getEndLine(), current.getEndColumn());
		
		
		return ret;
	}
	
	/**
	 * Estimates the memory footprint of MapMult operation depending on which input is put into distributed cache.
	 * This function is called by <code>optFindAppendMethod()</code> to decide the execution strategy, as well as by 
	 * piggybacking to decide the number of Map-side instructions to put into a single GMR job. 
	 * 
	 * @param m1_dim1 ?
	 * @param m1_dim2 ?
	 * @param m2_dim1 ?
	 * @param m2_dim2 ?
	 * @param m1_rpb ?
	 * @param m1_cpb ?
	 * @return memory footprint estimate
	 */
	public static double footprintInMapper( long m1_dim1, long m1_dim2, long m2_dim1, long m2_dim2, long m1_rpb, long m1_cpb ) {
		double footprint = 0;
		
		// size of left input (matrix block)
		footprint += OptimizerUtils.estimateSize(Math.min(m1_dim1, m1_rpb), Math.min(m1_dim2, m1_cpb));
		
		// size of right input (vector)
		footprint += OptimizerUtils.estimateSize(m2_dim1, m2_dim2);
		
		// size of the output (only boundary block is merged)
		footprint += OptimizerUtils.estimateSize(Math.min(m1_dim1, m1_rpb), Math.min(m1_dim2+m2_dim2, m1_cpb));
		
		return footprint;
	}
		
	private static AppendMethod optFindAppendSPMethod( long m1_dim1, long m1_dim2, long m2_dim1, long m2_dim2, long m1_rpb, long m1_cpb, long m2_nnz, boolean cbind, DataType dt )
	{
		if(FORCED_APPEND_METHOD != null) {
			return FORCED_APPEND_METHOD;
		}
		
		//check for best case (map-only w/o shuffle)		
		if((    m2_dim1 >= 1 && m2_dim2 >= 1   //rhs dims known 				
			&& (cbind && m2_dim2 <= m1_cpb    //rhs is smaller than column block 
			|| !cbind && m2_dim1 <= m1_rpb) ) //rhs is smaller than row block
			&& ((dt == DataType.MATRIX) || (dt == DataType.FRAME && cbind)))
		{
			if( OptimizerUtils.checkSparkBroadcastMemoryBudget(m2_dim1, m2_dim2, m1_rpb, m1_cpb, m2_nnz) ) {
				return AppendMethod.MR_MAPPEND;
			}
		}
		
		//check for in-block append (reduce-only)
		if( cbind && m1_dim2 >= 1 && m2_dim2 >= 0  //column dims known
			&& m1_dim2+m2_dim2 <= m1_cpb   //output has one column block
		  ||!cbind && m1_dim1 >= 1 && m2_dim1 >= 0 //row dims known
			&& m1_dim1+m2_dim1 <= m1_rpb   //output has one column block
		  || dt == DataType.FRAME ) 
		{
			return AppendMethod.MR_RAPPEND;
		}
		
		//note: below append methods are only supported for matrix, not frame
		
		//special case of block-aligned append line
		if( cbind && m1_dim2 % m1_cpb == 0 
		   || !cbind && m1_dim1 % m1_rpb == 0 ) 
		{
			return AppendMethod.SP_GAlignedAppend;
		}
		
		//general case (map and reduce)
		return AppendMethod.MR_GAPPEND; 	
	}
	
	public static boolean requiresReplication( Hop left, Hop right )
	{
		return (!(left.getDim2()>=1 && right.getDim2()>=1) //cols of any input unknown 
				||(left.getDim2() > 1 && right.getDim2()==1 && left.getDim2()>=left.getColsInBlock() ) //col MV and more than 1 block
				||(left.getDim1() > 1 && right.getDim1()==1 && left.getDim1()>=left.getRowsInBlock() )); //row MV and more than 1 block
	}

	private static MMBinaryMethod optFindMMBinaryMethodSpark(Hop left, Hop right) {
		long m1_dim1 = left.getDim1();
		long m1_dim2 = left.getDim2();
		long m2_dim1 =  right.getDim1();
		long m2_dim2 = right.getDim2();
		long m1_rpb = left.getRowsInBlock();
		long m1_cpb = left.getColsInBlock();
		
		//MR_BINARY_UAGG_CHAIN only applied if result is column/row vector of MV binary operation.
		if( OptimizerUtils.ALLOW_OPERATOR_FUSION
			&& right instanceof AggUnaryOp && right.getInput().get(0) == left  //e.g., P / rowSums(P)
			&& ((((AggUnaryOp) right).getDirection() == Direction.Row && m1_dim2 > 1 && m1_dim2 <= m1_cpb ) //single column block
			|| (((AggUnaryOp) right).getDirection() == Direction.Col && m1_dim1 > 1 && m1_dim1 <= m1_rpb ))) //single row block
		{
			return MMBinaryMethod.MR_BINARY_UAGG_CHAIN;
		}
		
		//MR_BINARY_M currently only applied for MV because potential partitioning job may cause additional latency for VV.
		if( m2_dim1 >= 1 && m2_dim2 >= 1 // rhs dims known 
			&& ((m1_dim2 >= 1 && m2_dim2 == 1)  //rhs column vector	
			  ||(m1_dim1 >= 1 && m2_dim1 == 1 )) ) //rhs row vector
		{
			double size = OptimizerUtils.estimateSize(m2_dim1, m2_dim2);
			if( OptimizerUtils.checkSparkBroadcastMemoryBudget(size) ) {
				return MMBinaryMethod.MR_BINARY_M;
			}
		}
		
		//MR_BINARY_R as robust fallback strategy
		return MMBinaryMethod.MR_BINARY_R;
	}
		
	@Override
	public void refreshSizeInformation()
	{
		Hop input1 = getInput().get(0);
		Hop input2 = getInput().get(1);
		DataType dt1 = input1.getDataType();
		DataType dt2 = input2.getDataType();
		
		if ( getDataType() == DataType.SCALAR ) 
		{
			//do nothing always known
			setDim1(0);
			setDim2(0);
		}
		else //MATRIX OUTPUT
		{
			//TODO quantile
			if( op == OpOp2.CBIND )
			{
				setDim1( input1.rowsKnown() ? input1.getDim1() : input2.getDim1() );
					
				//ensure both columns are known, otherwise dangerous underestimation due to +(-1)
				if( input1.colsKnown() && input2.colsKnown() )
					setDim2( input1.getDim2() + input2.getDim2() );
				else
					setDim2(-1);
				//ensure both nnz are known, otherwise dangerous underestimation due to +(-1)
				if( input1.getNnz()>0 && input2.getNnz()>0 )
					setNnz( input1.getNnz() + input2.getNnz() );
				else
					setNnz(-1);
			}
			else if( op == OpOp2.RBIND )
			{
				setDim2( colsKnown() ? input1.getDim2() : input2.getDim2() );
					
				//ensure both rows are known, otherwise dangerous underestimation due to +(-1)
				if( input1.rowsKnown() && input2.rowsKnown() )
					setDim1( input1.getDim1() + input2.getDim1() );
				else
					setDim1(-1);
				//ensure both nnz are known, otherwise dangerous underestimation due to +(-1)
				if( input1.getNnz()>0 && input2.getNnz()>0 )
					setNnz( input1.getNnz() + input2.getNnz() );
				else
					setNnz(-1);
			}
			else if ( op == OpOp2.SOLVE )
			{
				//normally the second input would be of equal size as the output 
				//however, since we use qr internally, it also supports squared first inputs
				setDim1( input1.getDim2() );
				setDim2( input2.getDim2() );
			}
			else //general case
			{
				long ldim1, ldim2, lnnz1 = -1;
				
				if( dt1 == DataType.MATRIX && dt2 == DataType.SCALAR )
				{
					ldim1 = input1.getDim1();
					ldim2 = input1.getDim2();
					lnnz1 = input1.getNnz();
				}
				else if( dt1 == DataType.SCALAR && dt2 == DataType.MATRIX  ) 
				{
					ldim1 = input2.getDim1();
					ldim2 = input2.getDim2();
				}
				else //MATRIX - MATRIX 
				{
					//propagate if either input is known, rows need always be identical,
					//for cols we need to be careful with regard to matrix-vector operations
					if( outer ) //OUTER VECTOR OPERATION
					{
						ldim1 = input1.getDim1();
						ldim2 = input2.getDim2();
					}
					else //GENERAL CASE
					{
						ldim1 = (input1.rowsKnown()) ? input1.getDim1()
							: ((input2.getDim1()>1)?input2.getDim1():-1);
						ldim2 = (input1.colsKnown()) ? input1.getDim2() 
							: ((input2.getDim2()>1)?input2.getDim2():-1);
						lnnz1 = input1.getNnz();
					}
				}
				
				setDim1( ldim1 );
				setDim2( ldim2 );
				
				//update nnz only if we can ensure exact results, 
				//otherwise propagated via worst-case estimates
				if( op == OpOp2.POW || (input2 instanceof LiteralOp 
					&& OptimizerUtils.isBinaryOpConditionalSparseSafeExact(op, (LiteralOp)input2)) )
				{
					setNnz( lnnz1 );
				}
				else if( (op == OpOp2.PLUS || op == OpOp2.MINUS) 
					&& ((input1.getNnz()==0 && input2.getNnz()>=0)
					|| (input1.getNnz()>=0 && input2.getNnz()==0)) )
					setNnz(input1.getNnz() + input2.getNnz());
			}
		}
	}
	
	@Override
	public Object clone() throws CloneNotSupportedException 
	{
		BinaryOp ret = new BinaryOp();
		
		//copy generic attributes
		ret.clone(this, false);
		
		//copy specific attributes
		ret.op = op;
		ret.outer = outer;
		ret._maxNumThreads = _maxNumThreads;
		
		return ret;
	}
	
	@Override
	public boolean compare( Hop that )
	{
		if( !(that instanceof BinaryOp) )
			return false;
		
		BinaryOp that2 = (BinaryOp)that;
		return (   op == that2.op
				&& outer == that2.outer
				&& _maxNumThreads == that2._maxNumThreads
				&& getInput().get(0) == that2.getInput().get(0)
				&& getInput().get(1) == that2.getInput().get(1));
	}
	
	public boolean supportsMatrixScalarOperations() {
		return ( op==OpOp2.PLUS ||op==OpOp2.MINUS
				||op==OpOp2.MULT ||op==OpOp2.DIV
				||op==OpOp2.MODULUS ||op==OpOp2.INTDIV
				||op==OpOp2.LESS ||op==OpOp2.LESSEQUAL
				||op==OpOp2.GREATER ||op==OpOp2.GREATEREQUAL
				||op==OpOp2.EQUAL ||op==OpOp2.NOTEQUAL
				||op==OpOp2.MIN ||op==OpOp2.MAX
				||op==OpOp2.LOG ||op==OpOp2.POW
				||op==OpOp2.AND ||op==OpOp2.OR ||op==OpOp2.XOR
				||op==OpOp2.BITWAND ||op==OpOp2.BITWOR ||op==OpOp2.BITWXOR
				||op==OpOp2.BITWSHIFTL ||op==OpOp2.BITWSHIFTR);
	}
	
	public boolean isPPredOperation() {
		return (op==OpOp2.LESS    ||op==OpOp2.LESSEQUAL
			||op==OpOp2.GREATER ||op==OpOp2.GREATEREQUAL
			||op==OpOp2.EQUAL   ||op==OpOp2.NOTEQUAL);
	}
	
	public OpOp2 getComplementPPredOperation() {
		switch( op ) {
			case LESS:         return OpOp2.GREATEREQUAL;
			case LESSEQUAL:    return OpOp2.GREATER;
			case GREATER:      return OpOp2.LESSEQUAL;
			case GREATEREQUAL: return OpOp2.LESS;
			case EQUAL:        return OpOp2.NOTEQUAL;
			case NOTEQUAL:     return OpOp2.EQUAL;
			default:
				throw new HopsException("BinaryOp is not a ppred operation.");
		}
	}
}
