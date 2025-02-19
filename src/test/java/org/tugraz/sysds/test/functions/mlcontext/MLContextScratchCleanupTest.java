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

package org.tugraz.sysds.test.functions.mlcontext;

import static org.tugraz.sysds.api.mlcontext.ScriptFactory.dmlFromFile;

import java.io.File;

import org.apache.spark.sql.SparkSession;
import org.junit.After;
import org.junit.Test;
import org.tugraz.sysds.api.DMLScript;
import org.tugraz.sysds.common.Types.ExecMode;
import org.tugraz.sysds.api.mlcontext.MLContext;
import org.tugraz.sysds.api.mlcontext.Matrix;
import org.tugraz.sysds.api.mlcontext.Script;
import org.tugraz.sysds.test.AutomatedTestBase;
import org.tugraz.sysds.test.TestUtils;


public class MLContextScratchCleanupTest extends AutomatedTestBase 
{
	private final static String TEST_DIR = "functions/mlcontext";
	private final static String TEST_NAME = "MLContextScratchCleanup";

	private final static int rows = 1123;
	private final static int cols = 789;
	
	@Override
	public void setUp() {
		TestUtils.clearAssertionInformation();
		addTestConfiguration(TEST_DIR, TEST_NAME);
		getAndLoadTestConfiguration(TEST_NAME);
	}

	@Test
	public void testMLContextMultipleScriptsCP() {
		runMLContextTestMultipleScript(ExecMode.SINGLE_NODE, false);
	}
	
	@Test
	public void testMLContextMultipleScriptsHybrid() {
		runMLContextTestMultipleScript(ExecMode.HYBRID, false);
	}
	
	@Test
	public void testMLContextMultipleScriptsSpark() {
		runMLContextTestMultipleScript(ExecMode.SPARK, false);
	}
	
	@Test
	public void testMLContextMultipleScriptsWithReadCP() {
		runMLContextTestMultipleScript(ExecMode.SINGLE_NODE, true);
	}
	
	@Test
	public void testMLContextMultipleScriptsWithReadHybrid() {
		runMLContextTestMultipleScript(ExecMode.HYBRID, true);
	}
	
	@Test
	public void testMLContextMultipleScriptsWithReadSpark() {
		runMLContextTestMultipleScript(ExecMode.SPARK, true);
	}

	private static void runMLContextTestMultipleScript(ExecMode platform, boolean wRead) 
	{
		ExecMode oldplatform = DMLScript.getGlobalExecMode();
		DMLScript.setGlobalExecMode(platform);
		
		//create mlcontext
		SparkSession spark = createSystemMLSparkSession("MLContextScratchCleanupTest", "local");
		MLContext ml = new MLContext(spark);
		ml.setExplain(true);

		String dml1 = baseDirectory + File.separator + "ScratchCleanup1.dml";
		String dml2 = baseDirectory + File.separator + (wRead?"ScratchCleanup2b.dml":"ScratchCleanup2.dml");
		
		try
		{
			Script script1 = dmlFromFile(dml1).in("$rows", rows).in("$cols", cols).out("X");
			Matrix X = ml.execute(script1).getMatrix("X");
			
			//clear in-memory/cached data to emulate on-disk storage
			X.toMatrixObject().clearData();
			
			Script script2 = dmlFromFile(dml2).in("X", X).out("z");
			String z = ml.execute(script2).getString("z");
			
			System.out.println(z);
		}
		catch(Exception ex) {
			throw new RuntimeException(ex);
		}
		finally {
			DMLScript.setGlobalExecMode(oldplatform);
			
			// stop underlying spark context to allow single jvm tests (otherwise the
			// next test that tries to create a SparkContext would fail)
			spark.stop();
			// clear status mlcontext and spark exec context
			ml.close();
		}
	}

	@After
	public void tearDown() {
		super.tearDown();
	}
}
