/**
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

package org.apache.lens.cube.parse;

import static org.apache.lens.cube.parse.CubeTestSetup.getDateUptoHours;
import static org.apache.lens.cube.parse.CubeTestSetup.now;
import static org.apache.lens.cube.parse.CubeTestSetup.twodaysBack;

import java.util.List;

import org.apache.hadoop.hive.conf.HiveConf;
import org.apache.hadoop.hive.ql.parse.ParseException;
import org.apache.hadoop.hive.ql.parse.SemanticException;
import org.apache.lens.cube.metadata.TestCubeMetastoreClient;
import org.apache.lens.cube.parse.CubeQueryContext;
import org.apache.lens.cube.parse.CubeQueryRewriter;
import org.apache.lens.cube.parse.TimeRange;
import org.testng.Assert;
import org.testng.annotations.AfterTest;
import org.testng.annotations.BeforeTest;
import org.testng.annotations.Test;

public class TestTimeRangeExtractor extends TestQueryRewrite {
  private CubeQueryRewriter driver;
  private String dateNow;
  private String dateTwoDaysBack;

  @BeforeTest
  public void setupInstance() throws Exception {
    driver = new CubeQueryRewriter(new HiveConf());
    dateTwoDaysBack = getDateUptoHours(twodaysBack);
    dateNow = getDateUptoHours(now);

  }

  @AfterTest
  public void closeInstance() throws Exception {
  }

  public static String rewrite(CubeQueryRewriter driver, String query) throws SemanticException, ParseException {
    CubeQueryContext rewrittenQuery = driver.rewrite(query);
    return rewrittenQuery.toHQL();
  }

  @Test
  public void testTimeRangeValidation() throws Exception {
    String timeRange2 = " time_range_in(dt, '" + dateNow + "','" + dateTwoDaysBack + "')";
    try {
      // this should throw exception because from date is after to date
      CubeQueryContext rewrittenQuery =
          driver.rewrite("SELECT cityid, testCube.msr2 from" + " testCube where " + timeRange2);
      Assert.fail("Should not reach here");
    } catch (SemanticException exc) {
      exc.printStackTrace();
      Assert.assertNotNull(exc);
    }
  }

  @Test
  public void testNoNPE() throws Exception {
    // GRILL-38 NPE in extracting time range
    String timeRange = " time_range_in(dt, '" + dateTwoDaysBack + "','" + dateNow + "')";
    String q1 = "SELECT cityid, testCube.msr2 from testCube where " + timeRange + " AND cityid IS NULL";
    rewrite(driver, q1);
    q1 = "SELECT cityid, testCube.msr2 from testCube where cityid IS NULL AND " + timeRange;
    rewrite(driver, q1);
  }

  @Test
  public void testTimeRangeASTPosition() throws Exception {
    // check that time range can be any child of AND
    String timeRange = " time_range_in(dt, '" + dateTwoDaysBack + "','" + dateNow + "')";
    String q1 = "SELECT cityid, testCube.msr2 from testCube where " + timeRange + " AND cityid=1";
    CubeQueryContext cubeql = driver.rewrite(q1);
    String hql = cubeql.toHQL();
  }

  @Test
  public void testPartitionColNameExtract() throws Exception {
    String q2 =
        "SELECT cityid, testCube.msr3 from testCube where cityid=1 AND " + " time_range_in(dt, '" + dateTwoDaysBack
            + "','" + dateNow + "')";
    CubeQueryContext cubeql = driver.rewrite(q2);
    String hql = cubeql.toHQL();
    // Check that column name in time range is extracted properly
    TimeRange range = cubeql.getTimeRanges().get(0);
    Assert.assertNotNull(range);
    Assert.assertEquals(TestCubeMetastoreClient.getDatePartitionKey(), range.getPartitionColumn(),
        "Time dimension should be " + TestCubeMetastoreClient.getDatePartitionKey());
  }

  @Test
  public void testTimeRangeWithinTimeRange() throws Exception {
    System.out.println("###");
    String dateTwoDaysBack = getDateUptoHours(twodaysBack);
    String dateNow = getDateUptoHours(now);
    // time range within time range
    String q3 =
        "SELECT cityid, testCube.msr3 FROM testCube where cityid=1 AND" + "  (time_range_in(dt, '" + dateTwoDaysBack
            + "','" + dateNow + "')  "
            // Time range as sibling of the first time range
            + " OR " + " time_range_in(dt, '" + dateTwoDaysBack + "', '" + dateNow + "'))";
    CubeQueryContext cubeql = driver.rewrite(q3);
    String hql = cubeql.toHQL();

    List<TimeRange> ranges = cubeql.getTimeRanges();
    Assert.assertEquals(2, ranges.size());

    TimeRange first = ranges.get(0);
    Assert.assertNotNull(first);
    Assert.assertEquals(dateTwoDaysBack, getDateUptoHours(first.getFromDate()));
    Assert.assertEquals(dateNow, getDateUptoHours(first.getToDate()));

    TimeRange second = ranges.get(1);
    Assert.assertNotNull(second);
    Assert.assertEquals("dt", second.getPartitionColumn());
    Assert.assertEquals(dateTwoDaysBack, getDateUptoHours(second.getFromDate()));
    Assert.assertEquals(dateNow, getDateUptoHours(second.getToDate()));
  }
}
