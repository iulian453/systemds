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

package org.tugraz.sysds.test.functions.data;

import org.apache.commons.lang.ArrayUtils;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;
import org.tugraz.sysds.common.Types.ExecMode;
import org.tugraz.sysds.test.AutomatedTestBase;
import org.tugraz.sysds.test.TestConfiguration;

import java.util.Arrays;
import java.util.Collection;

@RunWith(value = Parameterized.class)
public class TensorConstructionTest extends AutomatedTestBase
{

	private final static String TEST_DIR = "functions/data/";
	private final static String TEST_NAME = "TensorConstruction";
	private final static String TEST_CLASS_DIR = TEST_DIR + TensorConstructionTest.class.getSimpleName() + "/";

	private String value;
	private long[] dimensions;

	public TensorConstructionTest(long[] dims, String v) {
		dimensions = dims;
		value = v;
	}
	
	@Parameters
	public static Collection<Object[]> data() {
		Object[][] data = new Object[][] { 
				{new long[]{3, 4, 5}, "3"},
				{new long[]{1, 1}, "8"},
				{new long[]{7, 1, 1}, "0.5"},
				{new long[]{10, 2, 4}, "TRUE"},
				{new long[]{30, 40, 50}, "FALSE"},
				{new long[]{1000, 20}, "0"},
				{new long[]{100, 10, 10, 10, 10}, "0"},
				{new long[]{1, 1, 1, 1, 1, 1, 100}, "1"},
				};
		return Arrays.asList(data);
	}
	
	@Override
	public void setUp() 
	{
		addTestConfiguration(TEST_NAME,new TestConfiguration(TEST_CLASS_DIR, TEST_NAME,new String[]{"A.scalar"}));
	}

	@Test
	public void testTensorConstruction()
	{
		ExecMode platformOld = rtplatform;
		try
		{
			getAndLoadTestConfiguration(TEST_NAME);
			
			String HOME = SCRIPT_DIR + TEST_DIR;

			long length = Arrays.stream(dimensions).reduce(1, (a, b) -> a*b);
			StringBuilder values = new StringBuilder();
			for (long i = 0; i < length; i++) {
				values.append(i).append(" ");
			}
			fullDMLScriptName = HOME + TEST_NAME + ".dml";
			StringBuilder dimensionsStringBuilder = new StringBuilder();
			Arrays.stream(dimensions).forEach((dim) -> dimensionsStringBuilder.append(dim).append(" "));
			String dimensionsString = dimensionsStringBuilder.toString();

			StringBuilder reverseDimsStrBuilder = new StringBuilder();
			ArrayUtils.reverse(dimensions);
			Arrays.stream(dimensions).forEach((dim) -> reverseDimsStrBuilder.append(dim).append(" "));
			String reversedDimStr = reverseDimsStrBuilder.toString();

			programArgs = new String[]{"-explain", "-args",
				dimensionsString, Integer.toString(dimensions.length), value, values.toString(),
				reversedDimStr};

			// Generate Data in CP
			rtplatform = ExecMode.SINGLE_NODE;
			// TODO check tensors (write not implemented yet, so not possible)
			runTest(true, false, null, -1);
		}
		finally
		{
			rtplatform = platformOld;
		}
	}
}
