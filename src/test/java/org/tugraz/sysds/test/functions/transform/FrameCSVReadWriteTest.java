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

package org.tugraz.sysds.test.functions.transform;

import org.junit.Test;
import org.tugraz.sysds.api.DMLScript;
import org.tugraz.sysds.common.Types.ExecMode;
import org.tugraz.sysds.runtime.io.FileFormatPropertiesCSV;
import org.tugraz.sysds.runtime.io.FrameReader;
import org.tugraz.sysds.runtime.io.FrameReaderFactory;
import org.tugraz.sysds.runtime.matrix.data.FrameBlock;
import org.tugraz.sysds.runtime.matrix.data.InputInfo;
import org.tugraz.sysds.runtime.util.DataConverter;
import org.tugraz.sysds.test.AutomatedTestBase;
import org.tugraz.sysds.test.TestConfiguration;
import org.tugraz.sysds.test.TestUtils;

public class FrameCSVReadWriteTest extends AutomatedTestBase 
{
	private final static String TEST_NAME1 = "FrameCSVReadWrite";
	private final static String TEST_DIR = "functions/transform/";
	private final static String TEST_CLASS_DIR = TEST_DIR + FrameCSVReadWriteTest.class.getSimpleName() + "/";
	
	//dataset and transform tasks without missing values
	private final static String DATASET 	= "csv_mix/quotes1.csv";
	
	@Override
	public void setUp()  {
		TestUtils.clearAssertionInformation();
		addTestConfiguration(TEST_NAME1, 
			new TestConfiguration(TEST_CLASS_DIR, TEST_NAME1, new String[] { "R" }) );
	}
	
	@Test
	public void testCSVReadWriteSinglenode() {
		runCSVQuotesReadWriteTest(ExecMode.SINGLE_NODE, "csv");
	}
	
	@Test
	public void testCSVReadWriteHybrid() {
		runCSVQuotesReadWriteTest(ExecMode.HYBRID, "csv");
	}
	
	@Test
	public void testCSVReadWriteSpark() {
		runCSVQuotesReadWriteTest(ExecMode.SPARK, "csv");
	}
	
	
	/**
	 * 
	 * @param rt
	 * @param ofmt
	 * @param dataset
	 */
	private void runCSVQuotesReadWriteTest( ExecMode rt, String ofmt )
	{
		//set runtime platform
		ExecMode rtold = rtplatform;
		rtplatform = rt;

		boolean sparkConfigOld = DMLScript.USE_LOCAL_SPARK_CONFIG;
		if( rtplatform == ExecMode.SPARK || rtplatform == ExecMode.HYBRID)
			DMLScript.USE_LOCAL_SPARK_CONFIG = true;

		if( !ofmt.equals("csv") )
			throw new RuntimeException("Unsupported test output format");
		
		try
		{
			getAndLoadTestConfiguration(TEST_NAME1);
			
			String HOME = SCRIPT_DIR + TEST_DIR;
			fullDMLScriptName = HOME + TEST_NAME1 + ".dml";
			programArgs = new String[]{"-explain","-args", 
				HOME + "input/" + DATASET, output("R") };
	
			runTest(true, false, null, -1); 
			
			//read input/output and compare
			FrameReader reader1 = FrameReaderFactory.createFrameReader(InputInfo.CSVInputInfo, 
					new FileFormatPropertiesCSV(false, ",", false));
			FrameBlock fb1 = reader1.readFrameFromHDFS(HOME + "input/" + DATASET, -1L, -1L);
			FrameReader reader2 = FrameReaderFactory.createFrameReader(InputInfo.CSVInputInfo);
			FrameBlock fb2 = reader2.readFrameFromHDFS(output("R"), -1L, -1L);
			String[][] R1 = DataConverter.convertToStringFrame(fb1);
			String[][] R2 = DataConverter.convertToStringFrame(fb2);
			TestUtils.compareFrames(R1, R2, R1.length, R1[0].length);			
		}
		catch(Exception ex) {
			throw new RuntimeException(ex);
		}
		finally {
			rtplatform = rtold;
			DMLScript.USE_LOCAL_SPARK_CONFIG = sparkConfigOld;
		}
	}
}