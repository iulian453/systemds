/*
 * Copyright 2019 Graz University of Technology
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.tugraz.sysds.test.functions.builtin;

import org.junit.Test;
import org.tugraz.sysds.common.Types.ExecMode;
import org.tugraz.sysds.lops.LopProperties.ExecType;
import org.tugraz.sysds.runtime.matrix.data.MatrixValue.CellIndex;
import org.tugraz.sysds.test.AutomatedTestBase;
import org.tugraz.sysds.test.TestConfiguration;
import org.tugraz.sysds.test.TestUtils;

import java.util.HashMap;

public class BuiltinSTEPLmTest extends AutomatedTestBase {

	private final static String TEST_NAME = "steplm";
	private final static String TEST_DIR = "functions/builtin/";
	private static final String TEST_CLASS_DIR = TEST_DIR + BuiltinSTEPLmTest.class.getSimpleName() + "/";

	private final static double eps = 1e-10;
	private final static int rows = 10;
	private final static int cols = 3;
	private final static double spSparse = 0.3;
	private final static double spDense = 0.7;

	public enum LinregType {
		CG, DS, AUTO
	}

	@Override
	public void setUp() {
		addTestConfiguration(TEST_NAME, new TestConfiguration(TEST_CLASS_DIR, TEST_NAME, new String[]{"B"}));
	}

	@Test
	public void testLmMatrixDenseCPlm() {
		runSTEPLmTest(false, ExecType.CP, BuiltinSTEPLmTest.LinregType.AUTO);
	}

	@Test
	public void testLmMatrixSparseCPlm() {
		runSTEPLmTest(false, ExecType.CP, BuiltinSTEPLmTest.LinregType.AUTO);
	}

	@Test
	public void testLmMatrixDenseSPlm() {
		runSTEPLmTest(false, ExecType.SPARK, BuiltinSTEPLmTest.LinregType.AUTO);
	}

	@Test
	public void testLmMatrixSparseSPlm() {
		runSTEPLmTest(true, ExecType.SPARK, BuiltinSTEPLmTest.LinregType.AUTO);
	}

	private void runSTEPLmTest(boolean sparse, ExecType instType, BuiltinSTEPLmTest.LinregType linregAlgo) {
		ExecMode platformOld = setExecMode(instType);

		String dml_test_name = TEST_NAME;
		rtplatform = ExecMode.SINGLE_NODE;

		try {

			//disableOutAndExpectedDeletion();

			loadTestConfiguration(getTestConfiguration(TEST_NAME));
			double sparsity = sparse ? spSparse : spDense;

			String HOME = SCRIPT_DIR + TEST_DIR;

			fullDMLScriptName = HOME + dml_test_name + ".dml";
			programArgs = new String[]{"-explain", "-args", input("A"), input("B"), output("C"), output("S")};
			fullRScriptName = HOME + TEST_NAME + ".R";
			rCmd = "Rscript" + " " + fullRScriptName + " " + inputDir() + " " + expectedDir();

			//generate actual dataset
			double[][] A = getRandomMatrix(rows, cols, 0, 1, sparsity, 7);
			writeInputMatrixWithMTD("A", A, true);
			double[][] B = getRandomMatrix(rows, 1, 0, 10, 1.0, 3);
			writeInputMatrixWithMTD("B", B, true);

			runTest(true, false, null, -1);
			runRScript(true);

			//compare matrices
			HashMap<CellIndex, Double> dmlfile = readDMLMatrixFromHDFS("C");
			HashMap<CellIndex, Double> dmfile1 = readDMLMatrixFromHDFS("S");
			HashMap<CellIndex, Double> rfile = readRMatrixFromFS("C");
			HashMap<CellIndex, Double> rfile1 = readRMatrixFromFS("S");
			TestUtils.compareMatrices(dmlfile, rfile, eps, "Stat-DML", "Stat-R");
			TestUtils.compareMatrices(dmfile1, rfile1, eps, "Stat-DML", "Stat-R");
		}
		finally {
			rtplatform = platformOld;
		}
	}
}
