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

package org.tugraz.sysds.test.functions.frame;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.Assert;
import org.junit.Test;
import org.tugraz.sysds.api.DMLScript;
import org.tugraz.sysds.common.Types.ExecMode;
import org.tugraz.sysds.hops.BinaryOp;
import org.tugraz.sysds.hops.BinaryOp.AppendMethod;
import org.tugraz.sysds.common.Types.ValueType;
import org.tugraz.sysds.runtime.matrix.data.FrameBlock;
import org.tugraz.sysds.runtime.matrix.data.InputInfo;
import org.tugraz.sysds.runtime.matrix.data.OutputInfo;
import org.tugraz.sysds.runtime.meta.MatrixCharacteristics;
import org.tugraz.sysds.runtime.util.UtilFunctions;
import org.tugraz.sysds.test.AutomatedTestBase;
import org.tugraz.sysds.test.TestConfiguration;
import org.tugraz.sysds.test.TestUtils;

public class FrameAppendDistTest extends AutomatedTestBase
{
	private final static String TEST_NAME = "FrameAppend";
	private final static String TEST_DIR = "functions/frame/";
	private final static String TEST_CLASS_DIR = TEST_DIR + FrameAppendDistTest.class.getSimpleName() + "/";

	private final static double epsilon=0.0000000001;
	private final static int min=1;
	private final static int max=100;
	
	private final static int rows1 = 1692;
	private final static int rows2 = 1192;
	//usecase a: inblock single
	private final static int cols1a = 375;
	private final static int cols2a = 92;
	//usecase b: inblock multiple
	private final static int cols1b = 1059;
	//usecase c: outblock blocksize 
	private final static int cols1d = 1460;
	private final static int cols3d = 990;
	
		
	private final static double sparsity1 = 0.5;
	private final static double sparsity2 = 0.01;
	
	@Override
	public void setUp() {
		TestUtils.clearAssertionInformation();
		addTestConfiguration(TEST_NAME, new TestConfiguration(TEST_CLASS_DIR, TEST_NAME, 
				new String[] {"C"}));
	}

	@Test
	public void testAppendInBlock1DenseSP() {
		commonAppendTest(ExecMode.SPARK, rows1, rows1, cols1a, cols2a, false, AppendMethod.MR_RAPPEND, false);
	}   
	
	@Test
	public void testAppendInBlock1SparseSP() {
		commonAppendTest(ExecMode.SPARK, rows1, rows1, cols1a, cols2a, true, AppendMethod.MR_RAPPEND, false);
	}   
	
	@Test
	public void testAppendInBlock1DenseRBindSP() {
		commonAppendTest(ExecMode.SPARK, rows1, rows2, cols1a, cols1a, false, AppendMethod.MR_RAPPEND, true);
	}   
	
	@Test
	public void testAppendInBlock1SparseRBindSP() {
		commonAppendTest(ExecMode.SPARK, rows1, rows1, cols1a, cols1a, true, AppendMethod.MR_RAPPEND, true);
	}   
	
	//NOTE: mappend only applied for m2_cols<=blocksize
	@Test
	public void testMapAppendInBlock2DenseSP() {
		commonAppendTest(ExecMode.SPARK, rows1, rows1, cols1b, cols2a, false, AppendMethod.MR_MAPPEND, false);
	}
	
	@Test
	public void testMapAppendInBlock2SparseSP() {
		commonAppendTest(ExecMode.SPARK, rows1, rows1, cols1b, cols2a, true, AppendMethod.MR_MAPPEND, false);
	}
	
	@Test
	public void testMapAppendOutBlock2DenseSP() {
		commonAppendTest(ExecMode.SPARK, rows1, rows1, cols1d, cols3d, false, AppendMethod.MR_MAPPEND, false);
	}
	
	@Test
	public void testMapAppendOutBlock2SparseSP() {
		commonAppendTest(ExecMode.SPARK, rows1, rows1, cols1d, cols3d, true, AppendMethod.MR_MAPPEND, false);
	}
	
	/**
	 * 
	 * @param platform
	 * @param rows
	 * @param cols1
	 * @param cols2
	 * @param sparse
	 */
	public void commonAppendTest(ExecMode platform, int rows1, int rows2, int cols1, int cols2, boolean sparse, AppendMethod forcedAppendMethod, boolean rbind)
	{
		TestConfiguration config = getAndLoadTestConfiguration(TEST_NAME);
	    
		ExecMode prevPlfm=rtplatform;
		
		double sparsity = (sparse) ? sparsity2 : sparsity1; 
		boolean sparkConfigOld = DMLScript.USE_LOCAL_SPARK_CONFIG;
		
		try
		{
			if(forcedAppendMethod != null) {
				BinaryOp.FORCED_APPEND_METHOD = forcedAppendMethod;
			}
			rtplatform = platform;
			if( rtplatform == ExecMode.SPARK )
				DMLScript.USE_LOCAL_SPARK_CONFIG = true;
	
			config.addVariable("rows", rows1);
			config.addVariable("cols", cols1);
	          
			/* This is for running the junit test the new way, i.e., construct the arguments directly */
			String RI_HOME = SCRIPT_DIR + TEST_DIR;
			fullDMLScriptName = RI_HOME + TEST_NAME + ".dml";
			programArgs = new String[]{"-explain","-args",  input("A"), 
					                             Long.toString(rows1), 
					                             Long.toString(cols1),
								                 input("B"),
					                             Long.toString(rows2), 
								                 Long.toString(cols2),
		                                         output("C"),
		                                         (rbind? "rbind": "cbind")};
			fullRScriptName = RI_HOME + TEST_NAME + ".R";
			rCmd = "Rscript" + " " + fullRScriptName + " " + 
			       inputDir() + " " + expectedDir() + " " + (rbind? "rbind": "cbind");
	
			//initialize the frame data.
			ValueType[] lschemaA = genMixSchema(cols1);
			double[][] A = getRandomMatrix(rows1, cols1, min, max, sparsity, 1111 /*\\System.currentTimeMillis()*/);
			writeInputFrameWithMTD("A", A, true, lschemaA, OutputInfo.BinaryBlockOutputInfo);	        
	        
			ValueType[] lschemaB = genMixSchema(cols2);
			double[][] B = getRandomMatrix(rows2, cols2, min, max, sparsity, 2345 /*\\System.currentTimeMillis()*/);
			writeInputFrameWithMTD("B", B, true, lschemaB, OutputInfo.BinaryBlockOutputInfo);	        
	        	        
			boolean exceptionExpected = false;
			int expectedNumberOfJobs = -1;
			runTest(true, exceptionExpected, null, expectedNumberOfJobs);
			runRScript(true);

			ValueType[] lschemaAB = UtilFunctions.copyOf(lschemaA, lschemaB);
			
			for(String file: config.getOutputFiles())
			{
				FrameBlock frameBlock = readDMLFrameFromHDFS(file, InputInfo.BinaryBlockInputInfo);
				MatrixCharacteristics md = new MatrixCharacteristics(frameBlock.getNumRows(), frameBlock.getNumColumns(), -1, -1);
				FrameBlock frameRBlock = readRFrameFromHDFS(file+".csv", InputInfo.CSVInputInfo, md);
				verifyFrameData(frameBlock, frameRBlock, (ValueType[]) lschemaAB);
				System.out.println("File processed is " + file);
			}
		}
		catch(Exception ex) {
			ex.printStackTrace();
			throw new RuntimeException(ex);
		}
		finally
		{
			//reset execution platform
			rtplatform = prevPlfm;
			DMLScript.USE_LOCAL_SPARK_CONFIG = sparkConfigOld;
			BinaryOp.FORCED_APPEND_METHOD = null;
		}
	}
	
	ValueType[] genMixSchema(int cols)
	{
		List<ValueType> schemaMixedLargeListStr = Collections.nCopies(cols/4, ValueType.STRING);
		List<ValueType> schemaMixedLargeListDble  = Collections.nCopies(cols/4, ValueType.FP64);
		List<ValueType> schemaMixedLargeListInt  = Collections.nCopies(cols/4, ValueType.INT64);
		List<ValueType> schemaMixedLargeListBool  = Collections.nCopies(cols-(cols/4)*3, ValueType.BOOLEAN);

		final List<ValueType> schemaMixedLargeList = new ArrayList<ValueType>(schemaMixedLargeListStr);
		schemaMixedLargeList.addAll(schemaMixedLargeListDble);
		schemaMixedLargeList.addAll(schemaMixedLargeListInt);
		schemaMixedLargeList.addAll(schemaMixedLargeListBool);
		ValueType[] schemaMixedLarge = new ValueType[schemaMixedLargeList.size()];
		schemaMixedLarge = (ValueType[]) schemaMixedLargeList.toArray(schemaMixedLarge);
		
		return schemaMixedLarge;
	}
	
	private static void verifyFrameData(FrameBlock frame1, FrameBlock frame2, ValueType[] schema) {
		for ( int i=0; i<frame1.getNumRows(); i++ )
			for( int j=0; j<frame1.getNumColumns(); j++ )	{
				Object val1 = UtilFunctions.stringToObject(schema[j], UtilFunctions.objectToString(frame1.get(i, j)));
				Object val2 = UtilFunctions.stringToObject(schema[j], UtilFunctions.objectToString(frame2.get(i, j)));
				if( TestUtils.compareToR(schema[j], val1, val2, epsilon) != 0)
					Assert.fail("The DML data for cell ("+ i + "," + j + ") is " + val1 + 
							", not same as the R value " + val2);
			}
	}
}
