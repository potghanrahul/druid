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

package org.apache.druid.segment.join;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import org.apache.druid.java.util.common.Intervals;
import org.apache.druid.java.util.common.StringUtils;
import org.apache.druid.math.expr.ExprMacroTable;
import org.apache.druid.query.filter.ExpressionDimFilter;
import org.apache.druid.query.filter.Filter;
import org.apache.druid.query.filter.InDimFilter;
import org.apache.druid.query.filter.OrDimFilter;
import org.apache.druid.query.filter.SelectorDimFilter;
import org.apache.druid.segment.CursorBuildSpec;
import org.apache.druid.segment.CursorFactory;
import org.apache.druid.segment.ReferenceCountedSegmentProvider;
import org.apache.druid.segment.TopNOptimizationInspector;
import org.apache.druid.segment.VirtualColumns;
import org.apache.druid.segment.column.ColumnCapabilities;
import org.apache.druid.segment.column.ValueType;
import org.apache.druid.segment.filter.Filters;
import org.apache.druid.segment.filter.SelectorFilter;
import org.apache.druid.segment.join.filter.JoinFilterPreAnalysis;
import org.apache.druid.segment.join.lookup.LookupJoinable;
import org.apache.druid.segment.join.table.IndexedTableJoinable;
import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class HashJoinSegmentCursorFactoryTest extends BaseHashJoinSegmentCursorFactoryTest
{
  @Test
  public void test_getInterval_factToCountry()
  {
    Assert.assertEquals(
        Intervals.of("2015-09-12/2015-09-12T05:21:00.060Z"),
        makeFactToCountrySegment().getDataInterval()
    );
  }

  @Test
  public void test_getRowSignature_factToCountry()
  {
    Assert.assertEquals(
        ImmutableList.of(
            "__time",
            "channel",
            "regionIsoCode",
            "countryNumber",
            "countryIsoCode",
            "user",
            "isRobot",
            "isAnonymous",
            "namespace",
            "page",
            "delta",
            "channel_uniques",
            "c1.countryNumber",
            "c1.countryIsoCode",
            "c1.countryName"
        ),
        Lists.newArrayList(makeFactToCountrySegment().as(CursorFactory.class).getRowSignature().getColumnNames())
    );
  }

  @Test
  public void test_getColumnCapabilities_factToCountryFactColumn()
  {
    final ColumnCapabilities capabilities = makeFactToCountrySegment().as(CursorFactory.class).getColumnCapabilities("countryIsoCode");

    Assert.assertEquals(ValueType.STRING, capabilities.getType());
    Assert.assertTrue(capabilities.hasBitmapIndexes());
    Assert.assertTrue(capabilities.isDictionaryEncoded().isTrue());
    Assert.assertTrue(capabilities.areDictionaryValuesSorted().isTrue());
    Assert.assertTrue(capabilities.areDictionaryValuesUnique().isTrue());
  }

  @Test
  public void test_getColumnCapabilities_factToCountryJoinColumn()
  {
    final ColumnCapabilities capabilities = makeFactToCountrySegment().as(CursorFactory.class).getColumnCapabilities(
        FACT_TO_COUNTRY_ON_ISO_CODE_PREFIX + "countryIsoCode"
    );

    Assert.assertEquals(ValueType.STRING, capabilities.getType());
    Assert.assertFalse(capabilities.hasBitmapIndexes());
    Assert.assertFalse(capabilities.areDictionaryValuesUnique().isTrue());
    Assert.assertFalse(capabilities.areDictionaryValuesSorted().isTrue());
    Assert.assertTrue(capabilities.isDictionaryEncoded().isTrue());
  }

  @Test
  public void test_getColumnCapabilities_factToCountryNonexistentFactColumn()
  {
    final ColumnCapabilities capabilities = makeFactToCountrySegment().as(CursorFactory.class)
        .getColumnCapabilities("nonexistent");

    Assert.assertNull(capabilities);
  }

  @Test
  public void test_getColumnCapabilities_factToCountryNonexistentJoinColumn()
  {
    final ColumnCapabilities capabilities = makeFactToCountrySegment().as(CursorFactory.class)
        .getColumnCapabilities(FACT_TO_COUNTRY_ON_ISO_CODE_PREFIX + "nonexistent");

    Assert.assertNull(capabilities);
  }

  @Test
  public void test_getColumnCapabilities_complexTypeName_factToCountryFactColumn()
  {
    Assert.assertEquals(
        "hyperUnique",
        makeFactToCountrySegment().as(CursorFactory.class).getColumnCapabilities("channel_uniques").getComplexTypeName()
    );
  }

  @Test
  public void test_getColumnCapabilities_typeString_factToCountryFactColumn()
  {
    Assert.assertEquals(
        "COMPLEX<hyperUnique>",
        makeFactToCountrySegment().as(CursorFactory.class).getColumnCapabilities("channel_uniques").asTypeString()
    );
  }

  @Test
  public void test_getColumnCapabilities_typeString_factToCountryJoinColumn()
  {
    Assert.assertEquals(
        "STRING",
        makeFactToCountrySegment().as(CursorFactory.class)
                                  .getColumnCapabilities(FACT_TO_COUNTRY_ON_ISO_CODE_PREFIX + "countryName")
                                  .asTypeString()
    );
  }

  @Test
  public void test_makeCursor_factToCountryLeft()
  {
    List<JoinableClause> joinableClauses = ImmutableList.of(factToCountryOnIsoCode(JoinType.LEFT));

    JoinFilterPreAnalysis joinFilterPreAnalysis = makeDefaultConfigPreAnalysis(
        null,
        joinableClauses,
        VirtualColumns.EMPTY
    );
    JoinTestHelper.verifyCursor(
        new HashJoinSegmentCursorFactory(
            factSegment.as(CursorFactory.class),
            null,
            joinableClauses,
            joinFilterPreAnalysis
        ).makeCursorHolder(CursorBuildSpec.FULL_SCAN),
        ImmutableList.of(
            "page",
            "countryIsoCode",
            FACT_TO_COUNTRY_ON_ISO_CODE_PREFIX + "countryIsoCode",
            FACT_TO_COUNTRY_ON_ISO_CODE_PREFIX + "countryName",
            FACT_TO_COUNTRY_ON_ISO_CODE_PREFIX + "countryNumber"
        ),
        ImmutableList.of(
            new Object[]{"Talk:Oswald Tilghman", null, null, null, NULL_COUNTRY},
            new Object[]{"Rallicula", null, null, null, NULL_COUNTRY},
            new Object[]{"Peremptory norm", "AU", "AU", "Australia", 0L},
            new Object[]{"Apamea abruzzorum", null, null, null, NULL_COUNTRY},
            new Object[]{"Atractus flammigerus", null, null, null, NULL_COUNTRY},
            new Object[]{"Agama mossambica", null, null, null, NULL_COUNTRY},
            new Object[]{"Mathis Bolly", "MX", "MX", "Mexico", 10L},
            new Object[]{"유희왕 GX", "KR", "KR", "Republic of Korea", 9L},
            new Object[]{"青野武", "JP", "JP", "Japan", 8L},
            new Object[]{"Golpe de Estado en Chile de 1973", "CL", "CL", "Chile", 2L},
            new Object[]{"President of India", "US", "US", "United States", 13L},
            new Object[]{"Diskussion:Sebastian Schulz", "DE", "DE", "Germany", 3L},
            new Object[]{"Saison 9 de Secret Story", "FR", "FR", "France", 5L},
            new Object[]{"Glasgow", "GB", "GB", "United Kingdom", 6L},
            new Object[]{"Didier Leclair", "CA", "CA", "Canada", 1L},
            new Object[]{"Les Argonautes", "CA", "CA", "Canada", 1L},
            new Object[]{"Otjiwarongo Airport", "US", "US", "United States", 13L},
            new Object[]{"Sarah Michelle Gellar", "CA", "CA", "Canada", 1L},
            new Object[]{"DirecTV", "US", "US", "United States", 13L},
            new Object[]{"Carlo Curti", "US", "US", "United States", 13L},
            new Object[]{"Giusy Ferreri discography", "IT", "IT", "Italy", 7L},
            new Object[]{"Roma-Bangkok", "IT", "IT", "Italy", 7L},
            new Object[]{"Wendigo", "SV", "SV", "El Salvador", 12L},
            new Object[]{"Алиса в Зазеркалье", "NO", "NO", "Norway", 11L},
            new Object[]{"Gabinete Ministerial de Rafael Correa", "EC", "EC", "Ecuador", 4L},
            new Object[]{"Old Anatolian Turkish", "US", "US", "United States", 13L},
            new Object[]{"Cream Soda", "SU", "SU", "States United", 15L},
            new Object[]{"Orange Soda", "MatchNothing", null, null, NULL_COUNTRY},
            new Object[]{"History of Fourems", "MMMM", "MMMM", "Fourems", 205L}
        )
    );
  }

  @Test
  public void test_makeCursor_factToCountryLeftUsingLookup()
  {
    List<JoinableClause> joinableClauses = ImmutableList.of(factToCountryNameUsingIsoCodeLookup(JoinType.LEFT));

    JoinFilterPreAnalysis joinFilterPreAnalysis = makeDefaultConfigPreAnalysis(
        null,
        joinableClauses,
        VirtualColumns.EMPTY
    );
    JoinTestHelper.verifyCursor(
        new HashJoinSegmentCursorFactory(
            factSegment.as(CursorFactory.class),
            null,
            joinableClauses,
            joinFilterPreAnalysis
        ).makeCursorHolder(CursorBuildSpec.FULL_SCAN),
        ImmutableList.of(
            "page",
            "countryIsoCode",
            FACT_TO_COUNTRY_ON_ISO_CODE_PREFIX + "k",
            FACT_TO_COUNTRY_ON_ISO_CODE_PREFIX + "v"
        ),
        ImmutableList.of(
            new Object[]{"Talk:Oswald Tilghman", null, null, null},
            new Object[]{"Rallicula", null, null, null},
            new Object[]{"Peremptory norm", "AU", "AU", "Australia"},
            new Object[]{"Apamea abruzzorum", null, null, null},
            new Object[]{"Atractus flammigerus", null, null, null},
            new Object[]{"Agama mossambica", null, null, null},
            new Object[]{"Mathis Bolly", "MX", "MX", "Mexico"},
            new Object[]{"유희왕 GX", "KR", "KR", "Republic of Korea"},
            new Object[]{"青野武", "JP", "JP", "Japan"},
            new Object[]{"Golpe de Estado en Chile de 1973", "CL", "CL", "Chile"},
            new Object[]{"President of India", "US", "US", "United States"},
            new Object[]{"Diskussion:Sebastian Schulz", "DE", "DE", "Germany"},
            new Object[]{"Saison 9 de Secret Story", "FR", "FR", "France"},
            new Object[]{"Glasgow", "GB", "GB", "United Kingdom"},
            new Object[]{"Didier Leclair", "CA", "CA", "Canada"},
            new Object[]{"Les Argonautes", "CA", "CA", "Canada"},
            new Object[]{"Otjiwarongo Airport", "US", "US", "United States"},
            new Object[]{"Sarah Michelle Gellar", "CA", "CA", "Canada"},
            new Object[]{"DirecTV", "US", "US", "United States"},
            new Object[]{"Carlo Curti", "US", "US", "United States"},
            new Object[]{"Giusy Ferreri discography", "IT", "IT", "Italy"},
            new Object[]{"Roma-Bangkok", "IT", "IT", "Italy"},
            new Object[]{"Wendigo", "SV", "SV", "El Salvador"},
            new Object[]{"Алиса в Зазеркалье", "NO", "NO", "Norway"},
            new Object[]{"Gabinete Ministerial de Rafael Correa", "EC", "EC", "Ecuador"},
            new Object[]{"Old Anatolian Turkish", "US", "US", "United States"},
            new Object[]{"Cream Soda", "SU", "SU", "States United"},
            new Object[]{"Orange Soda", "MatchNothing", null, null},
            new Object[]{"History of Fourems", "MMMM", "MMMM", "Fourems"}
        )
    );
  }

  @Test
  public void test_makeCursor_factToCountryInner()
  {
    List<JoinableClause> joinableClauses = ImmutableList.of(factToCountryOnIsoCode(JoinType.INNER));
    JoinFilterPreAnalysis joinFilterPreAnalysis = makeDefaultConfigPreAnalysis(
        null,
        joinableClauses,
        VirtualColumns.EMPTY
    );
    JoinTestHelper.verifyCursor(
        new HashJoinSegmentCursorFactory(
            factSegment.as(CursorFactory.class),
            null,
            joinableClauses,
            joinFilterPreAnalysis
        ).makeCursorHolder(CursorBuildSpec.FULL_SCAN),
        ImmutableList.of(
            "page",
            "countryIsoCode",
            FACT_TO_COUNTRY_ON_ISO_CODE_PREFIX + "countryIsoCode",
            FACT_TO_COUNTRY_ON_ISO_CODE_PREFIX + "countryName",
            FACT_TO_COUNTRY_ON_ISO_CODE_PREFIX + "countryNumber"
        ),
        ImmutableList.of(
            new Object[]{"Peremptory norm", "AU", "AU", "Australia", 0L},
            new Object[]{"Mathis Bolly", "MX", "MX", "Mexico", 10L},
            new Object[]{"유희왕 GX", "KR", "KR", "Republic of Korea", 9L},
            new Object[]{"青野武", "JP", "JP", "Japan", 8L},
            new Object[]{"Golpe de Estado en Chile de 1973", "CL", "CL", "Chile", 2L},
            new Object[]{"President of India", "US", "US", "United States", 13L},
            new Object[]{"Diskussion:Sebastian Schulz", "DE", "DE", "Germany", 3L},
            new Object[]{"Saison 9 de Secret Story", "FR", "FR", "France", 5L},
            new Object[]{"Glasgow", "GB", "GB", "United Kingdom", 6L},
            new Object[]{"Didier Leclair", "CA", "CA", "Canada", 1L},
            new Object[]{"Les Argonautes", "CA", "CA", "Canada", 1L},
            new Object[]{"Otjiwarongo Airport", "US", "US", "United States", 13L},
            new Object[]{"Sarah Michelle Gellar", "CA", "CA", "Canada", 1L},
            new Object[]{"DirecTV", "US", "US", "United States", 13L},
            new Object[]{"Carlo Curti", "US", "US", "United States", 13L},
            new Object[]{"Giusy Ferreri discography", "IT", "IT", "Italy", 7L},
            new Object[]{"Roma-Bangkok", "IT", "IT", "Italy", 7L},
            new Object[]{"Wendigo", "SV", "SV", "El Salvador", 12L},
            new Object[]{"Алиса в Зазеркалье", "NO", "NO", "Norway", 11L},
            new Object[]{"Gabinete Ministerial de Rafael Correa", "EC", "EC", "Ecuador", 4L},
            new Object[]{"Old Anatolian Turkish", "US", "US", "United States", 13L},
            new Object[]{"Cream Soda", "SU", "SU", "States United", 15L},
            new Object[]{"History of Fourems", "MMMM", "MMMM", "Fourems", 205L}
        )
    );
  }

  @Test
  public void test_makeCursor_factToCountryInnerUsingLookup()
  {
    List<JoinableClause> joinableClauses = ImmutableList.of(factToCountryNameUsingIsoCodeLookup(JoinType.INNER));
    JoinFilterPreAnalysis joinFilterPreAnalysis = makeDefaultConfigPreAnalysis(
        null,
        joinableClauses,
        VirtualColumns.EMPTY
    );
    JoinTestHelper.verifyCursor(
        new HashJoinSegmentCursorFactory(
            factSegment.as(CursorFactory.class),
            null,
            joinableClauses,
            joinFilterPreAnalysis
        ).makeCursorHolder(CursorBuildSpec.FULL_SCAN),
        ImmutableList.of(
            "page",
            "countryIsoCode",
            FACT_TO_COUNTRY_ON_ISO_CODE_PREFIX + "k",
            FACT_TO_COUNTRY_ON_ISO_CODE_PREFIX + "v"
        ),
        ImmutableList.of(
            new Object[]{"Peremptory norm", "AU", "AU", "Australia"},
            new Object[]{"Mathis Bolly", "MX", "MX", "Mexico"},
            new Object[]{"유희왕 GX", "KR", "KR", "Republic of Korea"},
            new Object[]{"青野武", "JP", "JP", "Japan"},
            new Object[]{"Golpe de Estado en Chile de 1973", "CL", "CL", "Chile"},
            new Object[]{"President of India", "US", "US", "United States"},
            new Object[]{"Diskussion:Sebastian Schulz", "DE", "DE", "Germany"},
            new Object[]{"Saison 9 de Secret Story", "FR", "FR", "France"},
            new Object[]{"Glasgow", "GB", "GB", "United Kingdom"},
            new Object[]{"Didier Leclair", "CA", "CA", "Canada"},
            new Object[]{"Les Argonautes", "CA", "CA", "Canada"},
            new Object[]{"Otjiwarongo Airport", "US", "US", "United States"},
            new Object[]{"Sarah Michelle Gellar", "CA", "CA", "Canada"},
            new Object[]{"DirecTV", "US", "US", "United States"},
            new Object[]{"Carlo Curti", "US", "US", "United States"},
            new Object[]{"Giusy Ferreri discography", "IT", "IT", "Italy"},
            new Object[]{"Roma-Bangkok", "IT", "IT", "Italy"},
            new Object[]{"Wendigo", "SV", "SV", "El Salvador"},
            new Object[]{"Алиса в Зазеркалье", "NO", "NO", "Norway"},
            new Object[]{"Gabinete Ministerial de Rafael Correa", "EC", "EC", "Ecuador"},
            new Object[]{"Old Anatolian Turkish", "US", "US", "United States"},
            new Object[]{"Cream Soda", "SU", "SU", "States United"},
            new Object[]{"History of Fourems", "MMMM", "MMMM", "Fourems"}
        )
    );
  }

  @Test
  public void test_makeCursor_factToCountryInnerUsingCountryNumber()
  {
    // In non-SQL-compatible mode, we get an extra row, since the 'null' countryNumber for "Talk:Oswald Tilghman"
    // is interpreted as 0 (a.k.a. Australia).
    List<JoinableClause> joinableClauses = ImmutableList.of(factToCountryOnNumber(JoinType.INNER));
    Filter filter = new SelectorDimFilter("channel", "#en.wikipedia", null).toFilter();
    JoinFilterPreAnalysis joinFilterPreAnalysis = makeDefaultConfigPreAnalysis(
        filter,
        joinableClauses,
        VirtualColumns.EMPTY
    );
    JoinTestHelper.verifyCursor(
        new HashJoinSegmentCursorFactory(
            factSegment.as(CursorFactory.class),
            null,
            joinableClauses,
            joinFilterPreAnalysis
        ).makeCursorHolder(
            CursorBuildSpec.builder().setFilter(filter).build()
        ),
        ImmutableList.of(
            "page",
            "countryIsoCode",
            FACT_TO_COUNTRY_ON_NUMBER_PREFIX + "countryIsoCode",
            FACT_TO_COUNTRY_ON_NUMBER_PREFIX + "countryName",
            FACT_TO_COUNTRY_ON_NUMBER_PREFIX + "countryNumber"
        ),
        ImmutableList.of(
            new Object[]{"Peremptory norm", "AU", "AU", "Australia", 0L},
            new Object[]{"President of India", "US", "US", "United States", 13L},
            new Object[]{"Glasgow", "GB", "GB", "United Kingdom", 6L},
            new Object[]{"Otjiwarongo Airport", "US", "US", "United States", 13L},
            new Object[]{"Sarah Michelle Gellar", "CA", "CA", "Canada", 1L},
            new Object[]{"DirecTV", "US", "US", "United States", 13L},
            new Object[]{"Carlo Curti", "US", "US", "United States", 13L},
            new Object[]{"Giusy Ferreri discography", "IT", "IT", "Italy", 7L},
            new Object[]{"Roma-Bangkok", "IT", "IT", "Italy", 7L},
            new Object[]{"Old Anatolian Turkish", "US", "US", "United States", 13L},
            new Object[]{"Cream Soda", "SU", "SU", "States United", 15L},
            new Object[]{"History of Fourems", "MMMM", "MMMM", "Fourems", 205L}
        )
    );
  }

  @Test
  public void test_makeCursor_factToCountryInnerUsingCountryNumberUsingLookup()
  {
    // In non-SQL-compatible mode, we get an extra row, since the 'null' countryNumber for "Talk:Oswald Tilghman"
    // is interpreted as 0 (a.k.a. Australia).
    List<JoinableClause> joinableClauses = ImmutableList.of(factToCountryNameUsingNumberLookup(JoinType.INNER));
    Filter filter = new SelectorDimFilter("channel", "#en.wikipedia", null).toFilter();
    JoinFilterPreAnalysis joinFilterPreAnalysis = makeDefaultConfigPreAnalysis(
        filter,
        joinableClauses,
        VirtualColumns.EMPTY
    );
    JoinTestHelper.verifyCursor(
        new HashJoinSegmentCursorFactory(
            factSegment.as(CursorFactory.class),
            null,
            joinableClauses,
            joinFilterPreAnalysis
        ).makeCursorHolder(
            CursorBuildSpec.builder().setFilter(filter).build()
        ),
        ImmutableList.of(
            "page",
            "countryIsoCode",
            FACT_TO_COUNTRY_ON_NUMBER_PREFIX + "v"
        ),
        ImmutableList.of(
            new Object[]{"Peremptory norm", "AU", "Australia"},
            new Object[]{"President of India", "US", "United States"},
            new Object[]{"Glasgow", "GB", "United Kingdom"},
            new Object[]{"Otjiwarongo Airport", "US", "United States"},
            new Object[]{"Sarah Michelle Gellar", "CA", "Canada"},
            new Object[]{"DirecTV", "US", "United States"},
            new Object[]{"Carlo Curti", "US", "United States"},
            new Object[]{"Giusy Ferreri discography", "IT", "Italy"},
            new Object[]{"Roma-Bangkok", "IT", "Italy"},
            new Object[]{"Old Anatolian Turkish", "US", "United States"},
            new Object[]{"Cream Soda", "SU", "States United"},
            new Object[]{"History of Fourems", "MMMM", "Fourems"}
        )
    );
  }

  @Test
  public void test_makeCursor_factToCountryLeftWithFilterOnFacts()
  {
    List<JoinableClause> joinableClauses = ImmutableList.of(factToCountryOnIsoCode(JoinType.LEFT));
    Filter filter = new SelectorDimFilter("channel", "#de.wikipedia", null).toFilter();
    JoinFilterPreAnalysis joinFilterPreAnalysis = makeDefaultConfigPreAnalysis(
        filter,
        joinableClauses,
        VirtualColumns.EMPTY
    );
    JoinTestHelper.verifyCursor(
        new HashJoinSegmentCursorFactory(
            factSegment.as(CursorFactory.class),
            null,
            joinableClauses,
            joinFilterPreAnalysis
        ).makeCursorHolder(
            CursorBuildSpec.builder().setFilter(filter).build()
        ),
        ImmutableList.of(
            "page",
            "countryIsoCode",
            FACT_TO_COUNTRY_ON_ISO_CODE_PREFIX + "countryIsoCode",
            FACT_TO_COUNTRY_ON_ISO_CODE_PREFIX + "countryName",
            FACT_TO_COUNTRY_ON_ISO_CODE_PREFIX + "countryNumber"
        ),
        ImmutableList.of(
            new Object[]{"Diskussion:Sebastian Schulz", "DE", "DE", "Germany", 3L}
        )
    );
  }

  @Test
  public void test_makeCursor_factToCountryLeftWithFilterOnFactsUsingLookup()
  {
    List<JoinableClause> joinableClauses = ImmutableList.of(factToCountryNameUsingIsoCodeLookup(JoinType.LEFT));
    Filter filter = new SelectorDimFilter("channel", "#de.wikipedia", null).toFilter();
    JoinFilterPreAnalysis joinFilterPreAnalysis = makeDefaultConfigPreAnalysis(
        filter,
        joinableClauses,
        VirtualColumns.EMPTY
    );
    JoinTestHelper.verifyCursor(
        new HashJoinSegmentCursorFactory(
            factSegment.as(CursorFactory.class),
            null,
            joinableClauses,
            joinFilterPreAnalysis
        ).makeCursorHolder(
            CursorBuildSpec.builder().setFilter(filter).build()
        ),
        ImmutableList.of(
            "page",
            "countryIsoCode",
            FACT_TO_COUNTRY_ON_ISO_CODE_PREFIX + "k",
            FACT_TO_COUNTRY_ON_ISO_CODE_PREFIX + "v"
        ),
        ImmutableList.of(
            new Object[]{"Diskussion:Sebastian Schulz", "DE", "DE", "Germany"}
        )
    );
  }

  @Test
  public void test_makeCursor_factToCountryRightWithFilterOnLeftIsNull()
  {
    List<JoinableClause> joinableClauses = ImmutableList.of(factToCountryOnIsoCode(JoinType.RIGHT));
    Filter filter = new SelectorDimFilter("channel", null, null).toFilter();
    JoinFilterPreAnalysis joinFilterPreAnalysis = makeDefaultConfigPreAnalysis(
        filter,
        joinableClauses,
        VirtualColumns.EMPTY
    );
    HashJoinSegmentCursorFactory cursorFactory = new HashJoinSegmentCursorFactory(
        factSegment.as(CursorFactory.class),
        null,
        joinableClauses,
        joinFilterPreAnalysis
    );
    List<String> columns = ImmutableList.of(
        "page",
        "countryIsoCode",
        "countryNumber",
        FACT_TO_COUNTRY_ON_ISO_CODE_PREFIX + "countryIsoCode",
        FACT_TO_COUNTRY_ON_ISO_CODE_PREFIX + "countryName",
        FACT_TO_COUNTRY_ON_ISO_CODE_PREFIX + "countryNumber"
    );
    CursorBuildSpec buildSpec = CursorBuildSpec.builder().setFilter(filter).build();
    JoinTestHelper.verifyCursor(
        cursorFactory.makeCursorHolder(buildSpec),
        columns,
        ImmutableList.of(
            new Object[]{null, null, null, "AX", "Atlantis", 14L},
            new Object[]{null, null, null, "USCA", "Usca", 16L}
        )
    );
  }

  @Test
  public void test_makeCursor_factToCountryRightWithFilterOnLeftIsNullUsingLookup()
  {
    List<JoinableClause> joinableClauses = ImmutableList.of(factToCountryNameUsingIsoCodeLookup(JoinType.RIGHT));
    Filter filter = new SelectorDimFilter("channel", null, null).toFilter();
    JoinFilterPreAnalysis joinFilterPreAnalysis = makeDefaultConfigPreAnalysis(
        filter,
        joinableClauses,
        VirtualColumns.EMPTY
    );
    HashJoinSegmentCursorFactory cursorFactory = new HashJoinSegmentCursorFactory(
        factSegment.as(CursorFactory.class),
        null,
        joinableClauses,
        joinFilterPreAnalysis
    );
    List<String> columns = ImmutableList.of(
        "page",
        "countryIsoCode",
        "countryNumber",
        FACT_TO_COUNTRY_ON_ISO_CODE_PREFIX + "k",
        FACT_TO_COUNTRY_ON_ISO_CODE_PREFIX + "v"
    );
    CursorBuildSpec buildSpec = CursorBuildSpec.builder().setFilter(filter).build();
    JoinTestHelper.verifyCursor(
        cursorFactory.makeCursorHolder(buildSpec),
        columns,
        ImmutableList.of(
            new Object[]{null, null, null, "AX", "Atlantis"},
            new Object[]{null, null, null, "USCA", "Usca"}
        )
    );
  }

  @Test
  public void test_makeCursor_factToCountryFullWithFilterOnLeftIsNull()
  {
    List<JoinableClause> joinableClauses = ImmutableList.of(factToCountryOnIsoCode(JoinType.FULL));
    Filter filter = new SelectorDimFilter("channel", null, null).toFilter();
    JoinFilterPreAnalysis joinFilterPreAnalysis = makeDefaultConfigPreAnalysis(
        filter,
        joinableClauses,
        VirtualColumns.EMPTY
    );
    HashJoinSegmentCursorFactory cursorFactory = new HashJoinSegmentCursorFactory(
        factSegment.as(CursorFactory.class),
        null,
        joinableClauses,
        joinFilterPreAnalysis
    );
    List<String> columns = ImmutableList.of(
        "page",
        "countryIsoCode",
        "countryNumber",
        FACT_TO_COUNTRY_ON_ISO_CODE_PREFIX + "countryIsoCode",
        FACT_TO_COUNTRY_ON_ISO_CODE_PREFIX + "countryName",
        FACT_TO_COUNTRY_ON_ISO_CODE_PREFIX + "countryNumber"
    );
    CursorBuildSpec buildSpec = CursorBuildSpec.builder().setFilter(filter).build();
    JoinTestHelper.verifyCursor(
        cursorFactory.makeCursorHolder(buildSpec),
        columns,
        ImmutableList.of(
            new Object[]{null, null, null, "AX", "Atlantis", 14L},
            new Object[]{null, null, null, "USCA", "Usca", 16L}
        )
    );
  }

  @Test
  public void test_makeCursor_factToCountryFullWithFilterOnLeftIsNullUsingLookup()
  {
    List<JoinableClause> joinableClauses = ImmutableList.of(factToCountryNameUsingIsoCodeLookup(JoinType.FULL));
    Filter filter = new SelectorDimFilter("channel", null, null).toFilter();
    JoinFilterPreAnalysis joinFilterPreAnalysis = makeDefaultConfigPreAnalysis(
        filter,
        joinableClauses,
        VirtualColumns.EMPTY
    );
    HashJoinSegmentCursorFactory cursorFactory = new HashJoinSegmentCursorFactory(
        factSegment.as(CursorFactory.class),
        null,
        joinableClauses,
        joinFilterPreAnalysis
    );
    List<String> columns = ImmutableList.of(
        "page",
        "countryIsoCode",
        "countryNumber",
        FACT_TO_COUNTRY_ON_ISO_CODE_PREFIX + "k",
        FACT_TO_COUNTRY_ON_ISO_CODE_PREFIX + "v"
    );
    CursorBuildSpec buildSpec = CursorBuildSpec.builder().setFilter(filter).build();
    JoinTestHelper.verifyCursor(
        cursorFactory.makeCursorHolder(buildSpec),
        columns,
        ImmutableList.of(
            new Object[]{null, null, null, "AX", "Atlantis"},
            new Object[]{null, null, null, "USCA", "Usca"}
        )
    );
  }

  @Test
  public void test_makeCursor_factToCountryRightWithFilterOnJoinable()
  {
    List<JoinableClause> joinableClauses = ImmutableList.of(factToCountryOnIsoCode(JoinType.RIGHT));
    Filter filter = new SelectorDimFilter(
        FACT_TO_COUNTRY_ON_ISO_CODE_PREFIX + "countryName",
        "Germany",
        null
    ).toFilter();

    JoinFilterPreAnalysis joinFilterPreAnalysis = makeDefaultConfigPreAnalysis(
        filter,
        joinableClauses,
        VirtualColumns.EMPTY
    );
    JoinTestHelper.verifyCursor(
        new HashJoinSegmentCursorFactory(
            factSegment.as(CursorFactory.class),
            null,
            joinableClauses,
            joinFilterPreAnalysis
        ).makeCursorHolder(
            CursorBuildSpec.builder().setFilter(filter).build()
        ),
        ImmutableList.of(
            "page",
            "countryIsoCode",
            "countryNumber",
            FACT_TO_COUNTRY_ON_ISO_CODE_PREFIX + "countryIsoCode",
            FACT_TO_COUNTRY_ON_ISO_CODE_PREFIX + "countryName",
            FACT_TO_COUNTRY_ON_ISO_CODE_PREFIX + "countryNumber"
        ),
        ImmutableList.of(
            new Object[]{"Diskussion:Sebastian Schulz", "DE", 3L, "DE", "Germany", 3L}
        )
    );
  }

  @Test
  public void test_makeCursor_factToCountryRightWithFilterOnJoinableUsingLookup()
  {
    List<JoinableClause> joinableClauses = ImmutableList.of(factToCountryNameUsingIsoCodeLookup(JoinType.RIGHT));
    Filter filter = new SelectorDimFilter(
        FACT_TO_COUNTRY_ON_ISO_CODE_PREFIX + "v",
        "Germany",
        null
    ).toFilter();

    JoinFilterPreAnalysis joinFilterPreAnalysis = makeDefaultConfigPreAnalysis(
        filter,
        joinableClauses,
        VirtualColumns.EMPTY
    );
    JoinTestHelper.verifyCursor(
        new HashJoinSegmentCursorFactory(
            factSegment.as(CursorFactory.class),
            null,
            joinableClauses,
            joinFilterPreAnalysis
        ).makeCursorHolder(
            CursorBuildSpec.builder().setFilter(filter).build()
        ),
        ImmutableList.of(
            "page",
            "countryIsoCode",
            "countryNumber",
            FACT_TO_COUNTRY_ON_ISO_CODE_PREFIX + "k",
            FACT_TO_COUNTRY_ON_ISO_CODE_PREFIX + "v"
        ),
        ImmutableList.of(
            new Object[]{"Diskussion:Sebastian Schulz", "DE", 3L, "DE", "Germany"}
        )
    );
  }

  @Test
  public void test_makeCursor_factToCountryLeftWithFilterOnJoinable()
  {
    List<JoinableClause> joinableClauses = ImmutableList.of(factToCountryOnIsoCode(JoinType.LEFT));

    Filter filter = new OrDimFilter(
        new SelectorDimFilter(FACT_TO_COUNTRY_ON_ISO_CODE_PREFIX + "countryIsoCode", "DE", null),
        new SelectorDimFilter(FACT_TO_COUNTRY_ON_ISO_CODE_PREFIX + "countryName", "Norway", null),
        new SelectorDimFilter(FACT_TO_COUNTRY_ON_ISO_CODE_PREFIX + "countryNumber", "10", null)
    ).toFilter();

    JoinFilterPreAnalysis joinFilterPreAnalysis = makeDefaultConfigPreAnalysis(
        filter,
        joinableClauses,
        VirtualColumns.EMPTY
    );
    JoinTestHelper.verifyCursor(
        new HashJoinSegmentCursorFactory(
            factSegment.as(CursorFactory.class),
            null,
            joinableClauses,
            joinFilterPreAnalysis
        ).makeCursorHolder(
            CursorBuildSpec.builder().setFilter(filter).build()
        ),
        ImmutableList.of(
            "page",
            "countryIsoCode",
            FACT_TO_COUNTRY_ON_ISO_CODE_PREFIX + "countryIsoCode",
            FACT_TO_COUNTRY_ON_ISO_CODE_PREFIX + "countryName",
            FACT_TO_COUNTRY_ON_ISO_CODE_PREFIX + "countryNumber"
        ),
        ImmutableList.of(
            new Object[]{"Mathis Bolly", "MX", "MX", "Mexico", 10L},
            new Object[]{"Diskussion:Sebastian Schulz", "DE", "DE", "Germany", 3L},
            new Object[]{"Алиса в Зазеркалье", "NO", "NO", "Norway", 11L}
        )
    );
  }

  @Test
  public void test_makeCursor_factToCountryLeftWithFilterOnJoinableUsingLookup()
  {
    List<JoinableClause> joinableClauses = ImmutableList.of(factToCountryNameUsingIsoCodeLookup(JoinType.LEFT));
    Filter filter = new OrDimFilter(
        new SelectorDimFilter(FACT_TO_COUNTRY_ON_ISO_CODE_PREFIX + "k", "DE", null),
        new SelectorDimFilter(FACT_TO_COUNTRY_ON_ISO_CODE_PREFIX + "v", "Norway", null)
    ).toFilter();

    JoinFilterPreAnalysis joinFilterPreAnalysis = makeDefaultConfigPreAnalysis(
        filter,
        joinableClauses,
        VirtualColumns.EMPTY
    );
    JoinTestHelper.verifyCursor(
        new HashJoinSegmentCursorFactory(
            factSegment.as(CursorFactory.class),
            null,
            joinableClauses,
            joinFilterPreAnalysis
        ).makeCursorHolder(
            CursorBuildSpec.builder().setFilter(filter).build()
        ),
        ImmutableList.of(
            "page",
            "countryIsoCode",
            FACT_TO_COUNTRY_ON_ISO_CODE_PREFIX + "k",
            FACT_TO_COUNTRY_ON_ISO_CODE_PREFIX + "v"
        ),
        ImmutableList.of(
            new Object[]{"Diskussion:Sebastian Schulz", "DE", "DE", "Germany"},
            new Object[]{"Алиса в Зазеркалье", "NO", "NO", "Norway"}
        )
    );
  }

  @Test
  public void test_makeCursor_factToCountryInnerWithFilterInsteadOfRealJoinCondition()
  {
    // Join condition => always true.
    // Filter => Fact to countries on countryIsoCode.

    List<JoinableClause> joinableClauses = ImmutableList.of(
        new JoinableClause(
            FACT_TO_COUNTRY_ON_ISO_CODE_PREFIX,
            new IndexedTableJoinable(countriesTable),
            JoinType.INNER,
            JoinConditionAnalysis.forExpression(
                "1",
                FACT_TO_COUNTRY_ON_ISO_CODE_PREFIX,
                ExprMacroTable.nil()
            )
        )
    );

    Filter filter = new ExpressionDimFilter(
        StringUtils.format("\"%scountryIsoCode\" == countryIsoCode", FACT_TO_COUNTRY_ON_ISO_CODE_PREFIX),
        ExprMacroTable.nil()
    ).toFilter();

    JoinFilterPreAnalysis joinFilterPreAnalysis = makeDefaultConfigPreAnalysis(
        filter,
        joinableClauses,
        VirtualColumns.EMPTY
    );
    JoinTestHelper.verifyCursor(
        new HashJoinSegmentCursorFactory(
            factSegment.as(CursorFactory.class),
            null,
            joinableClauses,
            joinFilterPreAnalysis
        ).makeCursorHolder(
            CursorBuildSpec.builder().setFilter(filter).build()
        ),
        ImmutableList.of(
            "page",
            "countryIsoCode",
            FACT_TO_COUNTRY_ON_ISO_CODE_PREFIX + "countryIsoCode",
            FACT_TO_COUNTRY_ON_ISO_CODE_PREFIX + "countryName",
            FACT_TO_COUNTRY_ON_ISO_CODE_PREFIX + "countryNumber"
        ),
        ImmutableList.of(
            new Object[]{"Peremptory norm", "AU", "AU", "Australia", 0L},
            new Object[]{"Mathis Bolly", "MX", "MX", "Mexico", 10L},
            new Object[]{"유희왕 GX", "KR", "KR", "Republic of Korea", 9L},
            new Object[]{"青野武", "JP", "JP", "Japan", 8L},
            new Object[]{"Golpe de Estado en Chile de 1973", "CL", "CL", "Chile", 2L},
            new Object[]{"President of India", "US", "US", "United States", 13L},
            new Object[]{"Diskussion:Sebastian Schulz", "DE", "DE", "Germany", 3L},
            new Object[]{"Saison 9 de Secret Story", "FR", "FR", "France", 5L},
            new Object[]{"Glasgow", "GB", "GB", "United Kingdom", 6L},
            new Object[]{"Didier Leclair", "CA", "CA", "Canada", 1L},
            new Object[]{"Les Argonautes", "CA", "CA", "Canada", 1L},
            new Object[]{"Otjiwarongo Airport", "US", "US", "United States", 13L},
            new Object[]{"Sarah Michelle Gellar", "CA", "CA", "Canada", 1L},
            new Object[]{"DirecTV", "US", "US", "United States", 13L},
            new Object[]{"Carlo Curti", "US", "US", "United States", 13L},
            new Object[]{"Giusy Ferreri discography", "IT", "IT", "Italy", 7L},
            new Object[]{"Roma-Bangkok", "IT", "IT", "Italy", 7L},
            new Object[]{"Wendigo", "SV", "SV", "El Salvador", 12L},
            new Object[]{"Алиса в Зазеркалье", "NO", "NO", "Norway", 11L},
            new Object[]{"Gabinete Ministerial de Rafael Correa", "EC", "EC", "Ecuador", 4L},
            new Object[]{"Old Anatolian Turkish", "US", "US", "United States", 13L},
            new Object[]{"Cream Soda", "SU", "SU", "States United", 15L},
            new Object[]{"History of Fourems", "MMMM", "MMMM", "Fourems", 205L}
        )
    );
  }

  @Test
  public void test_makeCursor_factToCountryInnerWithFilterInsteadOfRealJoinConditionUsingLookup()
  {
    // Join condition => always true.
    // Filter => Fact to countries on countryIsoCode.

    List<JoinableClause> joinableClauses = ImmutableList.of(
        new JoinableClause(
            FACT_TO_COUNTRY_ON_ISO_CODE_PREFIX,
            LookupJoinable.wrap(countryIsoCodeToNameLookup),
            JoinType.INNER,
            JoinConditionAnalysis.forExpression(
                "1",
                FACT_TO_COUNTRY_ON_ISO_CODE_PREFIX,
                ExprMacroTable.nil()
            )
        )
    );

    Filter filter = new ExpressionDimFilter(
        StringUtils.format("\"%sk\" == countryIsoCode", FACT_TO_COUNTRY_ON_ISO_CODE_PREFIX),
        ExprMacroTable.nil()
    ).toFilter();
    JoinFilterPreAnalysis joinFilterPreAnalysis = makeDefaultConfigPreAnalysis(
        filter,
        joinableClauses,
        VirtualColumns.EMPTY
    );
    JoinTestHelper.verifyCursor(
        new HashJoinSegmentCursorFactory(
            factSegment.as(CursorFactory.class),
            null,
            joinableClauses,
            joinFilterPreAnalysis
        ).makeCursorHolder(
            CursorBuildSpec.builder().setFilter(filter).build()
        ),
        ImmutableList.of(
            "page",
            "countryIsoCode",
            FACT_TO_COUNTRY_ON_ISO_CODE_PREFIX + "k",
            FACT_TO_COUNTRY_ON_ISO_CODE_PREFIX + "v"
        ),
        ImmutableList.of(
            new Object[]{"Peremptory norm", "AU", "AU", "Australia"},
            new Object[]{"Mathis Bolly", "MX", "MX", "Mexico"},
            new Object[]{"유희왕 GX", "KR", "KR", "Republic of Korea"},
            new Object[]{"青野武", "JP", "JP", "Japan"},
            new Object[]{"Golpe de Estado en Chile de 1973", "CL", "CL", "Chile"},
            new Object[]{"President of India", "US", "US", "United States"},
            new Object[]{"Diskussion:Sebastian Schulz", "DE", "DE", "Germany"},
            new Object[]{"Saison 9 de Secret Story", "FR", "FR", "France"},
            new Object[]{"Glasgow", "GB", "GB", "United Kingdom"},
            new Object[]{"Didier Leclair", "CA", "CA", "Canada"},
            new Object[]{"Les Argonautes", "CA", "CA", "Canada"},
            new Object[]{"Otjiwarongo Airport", "US", "US", "United States"},
            new Object[]{"Sarah Michelle Gellar", "CA", "CA", "Canada"},
            new Object[]{"DirecTV", "US", "US", "United States"},
            new Object[]{"Carlo Curti", "US", "US", "United States"},
            new Object[]{"Giusy Ferreri discography", "IT", "IT", "Italy"},
            new Object[]{"Roma-Bangkok", "IT", "IT", "Italy"},
            new Object[]{"Wendigo", "SV", "SV", "El Salvador"},
            new Object[]{"Алиса в Зазеркалье", "NO", "NO", "Norway"},
            new Object[]{"Gabinete Ministerial de Rafael Correa", "EC", "EC", "Ecuador"},
            new Object[]{"Old Anatolian Turkish", "US", "US", "United States"},
            new Object[]{"Cream Soda", "SU", "SU", "States United"},
            new Object[]{"History of Fourems", "MMMM", "MMMM", "Fourems"}
        )
    );
  }

  @Test
  public void test_makeCursor_factToRegionToCountryLeft()
  {
    List<JoinableClause> joinableClauses = ImmutableList.of(
        factToRegion(JoinType.LEFT),
        regionToCountry(JoinType.LEFT)
    );
    JoinFilterPreAnalysis joinFilterPreAnalysis = makeDefaultConfigPreAnalysis(
        null,
        joinableClauses,
        VirtualColumns.EMPTY
    );
    JoinTestHelper.verifyCursor(
        new HashJoinSegmentCursorFactory(
            factSegment.as(CursorFactory.class),
            null,
            joinableClauses,
            joinFilterPreAnalysis
        ).makeCursorHolder(CursorBuildSpec.FULL_SCAN),
        ImmutableList.of(
            "page",
            FACT_TO_REGION_PREFIX + "regionName",
            REGION_TO_COUNTRY_PREFIX + "countryName"
        ),
        ImmutableList.of(
            new Object[]{"Talk:Oswald Tilghman", null, null},
            new Object[]{"Rallicula", null, null},
            new Object[]{"Peremptory norm", "New South Wales", "Australia"},
            new Object[]{"Apamea abruzzorum", null, null},
            new Object[]{"Atractus flammigerus", null, null},
            new Object[]{"Agama mossambica", null, null},
            new Object[]{"Mathis Bolly", "Mexico City", "Mexico"},
            new Object[]{"유희왕 GX", "Seoul", "Republic of Korea"},
            new Object[]{"青野武", "Tōkyō", "Japan"},
            new Object[]{"Golpe de Estado en Chile de 1973", "Santiago Metropolitan", "Chile"},
            new Object[]{"President of India", "California", "United States"},
            new Object[]{"Diskussion:Sebastian Schulz", "Hesse", "Germany"},
            new Object[]{"Saison 9 de Secret Story", "Val d'Oise", "France"},
            new Object[]{"Glasgow", "Kingston upon Hull", "United Kingdom"},
            new Object[]{"Didier Leclair", "Ontario", "Canada"},
            new Object[]{"Les Argonautes", "Quebec", "Canada"},
            new Object[]{"Otjiwarongo Airport", "California", "United States"},
            new Object[]{"Sarah Michelle Gellar", "Ontario", "Canada"},
            new Object[]{"DirecTV", "North Carolina", "United States"},
            new Object[]{"Carlo Curti", "California", "United States"},
            new Object[]{"Giusy Ferreri discography", "Provincia di Varese", "Italy"},
            new Object[]{"Roma-Bangkok", "Provincia di Varese", "Italy"},
            new Object[]{"Wendigo", "Departamento de San Salvador", "El Salvador"},
            new Object[]{"Алиса в Зазеркалье", "Finnmark Fylke", "Norway"},
            new Object[]{"Gabinete Ministerial de Rafael Correa", "Provincia del Guayas", "Ecuador"},
            new Object[]{"Old Anatolian Turkish", "Virginia", "United States"},
            new Object[]{"Cream Soda", "Ainigriv", "States United"},
            new Object[]{"Orange Soda", null, null},
            new Object[]{"History of Fourems", "Fourems Province", "Fourems"}
        )
    );
  }

  @Test
  public void test_makeCursor_factToRegionToCountryInnerIncludeNull()
  {
    List<JoinableClause> joinableClauses = ImmutableList.of(
        factToRegionIncludeNull(JoinType.INNER),
        regionToCountry(JoinType.LEFT)
    );
    JoinFilterPreAnalysis joinFilterPreAnalysis = makeDefaultConfigPreAnalysis(
        null,
        joinableClauses,
        VirtualColumns.EMPTY
    );
    JoinTestHelper.verifyCursor(
        new HashJoinSegmentCursorFactory(
            factSegment.as(CursorFactory.class),
            null,
            joinableClauses,
            joinFilterPreAnalysis
        ).makeCursorHolder(CursorBuildSpec.FULL_SCAN),
        ImmutableList.of(
            "page",
            FACT_TO_REGION_PREFIX + "regionName",
            REGION_TO_COUNTRY_PREFIX + "countryName"
        ),
        ImmutableList.of(
            new Object[]{"Talk:Oswald Tilghman", "Nulland", null},
            new Object[]{"Rallicula", "Nulland", null},
            new Object[]{"Peremptory norm", "New South Wales", "Australia"},
            new Object[]{"Apamea abruzzorum", "Nulland", null},
            new Object[]{"Atractus flammigerus", "Nulland", null},
            new Object[]{"Agama mossambica", "Nulland", null},
            new Object[]{"Mathis Bolly", "Mexico City", "Mexico"},
            new Object[]{"유희왕 GX", "Seoul", "Republic of Korea"},
            new Object[]{"青野武", "Tōkyō", "Japan"},
            new Object[]{"Golpe de Estado en Chile de 1973", "Santiago Metropolitan", "Chile"},
            new Object[]{"President of India", "California", "United States"},
            new Object[]{"Diskussion:Sebastian Schulz", "Hesse", "Germany"},
            new Object[]{"Saison 9 de Secret Story", "Val d'Oise", "France"},
            new Object[]{"Glasgow", "Kingston upon Hull", "United Kingdom"},
            new Object[]{"Didier Leclair", "Ontario", "Canada"},
            new Object[]{"Les Argonautes", "Quebec", "Canada"},
            new Object[]{"Otjiwarongo Airport", "California", "United States"},
            new Object[]{"Sarah Michelle Gellar", "Ontario", "Canada"},
            new Object[]{"DirecTV", "North Carolina", "United States"},
            new Object[]{"Carlo Curti", "California", "United States"},
            new Object[]{"Giusy Ferreri discography", "Provincia di Varese", "Italy"},
            new Object[]{"Roma-Bangkok", "Provincia di Varese", "Italy"},
            new Object[]{"Wendigo", "Departamento de San Salvador", "El Salvador"},
            new Object[]{"Алиса в Зазеркалье", "Finnmark Fylke", "Norway"},
            new Object[]{"Gabinete Ministerial de Rafael Correa", "Provincia del Guayas", "Ecuador"},
            new Object[]{"Old Anatolian Turkish", "Virginia", "United States"},
            new Object[]{"Cream Soda", "Ainigriv", "States United"},
            new Object[]{"History of Fourems", "Fourems Province", "Fourems"}
        )
    );
  }

  @Test
  public void test_makeCursor_factToCountryAlwaysTrue()
  {
    List<JoinableClause> joinableClauses = ImmutableList.of(
        new JoinableClause(
            FACT_TO_COUNTRY_ON_ISO_CODE_PREFIX,
            new IndexedTableJoinable(countriesTable),
            JoinType.LEFT,
            JoinConditionAnalysis.forExpression(
                "1",
                FACT_TO_COUNTRY_ON_ISO_CODE_PREFIX,
                ExprMacroTable.nil()
            )
        )
    );

    Filter filter = new SelectorDimFilter("channel", "#de.wikipedia", null).toFilter();
    JoinFilterPreAnalysis joinFilterPreAnalysis = makeDefaultConfigPreAnalysis(
        filter,
        joinableClauses,
        VirtualColumns.EMPTY
    );
    HashJoinSegmentCursorFactory cursorFactory = new HashJoinSegmentCursorFactory(
        factSegment.as(CursorFactory.class),
        null,
        joinableClauses,
        joinFilterPreAnalysis
    );
    List<String> columns = ImmutableList.of(
        "page",
        FACT_TO_COUNTRY_ON_ISO_CODE_PREFIX + "countryName"
    );
    CursorBuildSpec buildSpec = CursorBuildSpec.builder().setFilter(filter).build();
    JoinTestHelper.verifyCursor(
        cursorFactory.makeCursorHolder(buildSpec),
        columns,
        ImmutableList.of(
            new Object[]{"Diskussion:Sebastian Schulz", "Australia"},
            new Object[]{"Diskussion:Sebastian Schulz", "Canada"},
            new Object[]{"Diskussion:Sebastian Schulz", "Chile"},
            new Object[]{"Diskussion:Sebastian Schulz", "Germany"},
            new Object[]{"Diskussion:Sebastian Schulz", "Ecuador"},
            new Object[]{"Diskussion:Sebastian Schulz", "France"},
            new Object[]{"Diskussion:Sebastian Schulz", "United Kingdom"},
            new Object[]{"Diskussion:Sebastian Schulz", "Italy"},
            new Object[]{"Diskussion:Sebastian Schulz", "Japan"},
            new Object[]{"Diskussion:Sebastian Schulz", "Republic of Korea"},
            new Object[]{"Diskussion:Sebastian Schulz", "Mexico"},
            new Object[]{"Diskussion:Sebastian Schulz", "Norway"},
            new Object[]{"Diskussion:Sebastian Schulz", "El Salvador"},
            new Object[]{"Diskussion:Sebastian Schulz", "United States"},
            new Object[]{"Diskussion:Sebastian Schulz", "Atlantis"},
            new Object[]{"Diskussion:Sebastian Schulz", "States United"},
            new Object[]{"Diskussion:Sebastian Schulz", "Usca"},
            new Object[]{"Diskussion:Sebastian Schulz", "Fourems"}
        )
    );
  }

  @Test
  public void test_makeCursor_factToCountryAlwaysFalse()
  {
    List<JoinableClause> joinableClauses = ImmutableList.of(
        new JoinableClause(
            FACT_TO_COUNTRY_ON_ISO_CODE_PREFIX,
            new IndexedTableJoinable(countriesTable),
            JoinType.LEFT,
            JoinConditionAnalysis.forExpression(
                "0",
                FACT_TO_COUNTRY_ON_ISO_CODE_PREFIX,
                ExprMacroTable.nil()
            )
        )
    );

    Filter filter = new SelectorDimFilter("channel", "#de.wikipedia", null).toFilter();

    JoinFilterPreAnalysis joinFilterPreAnalysis = makeDefaultConfigPreAnalysis(
        filter,
        joinableClauses,
        VirtualColumns.EMPTY
    );

    JoinTestHelper.verifyCursor(
        new HashJoinSegmentCursorFactory(
            factSegment.as(CursorFactory.class),
            null,
            joinableClauses,
            joinFilterPreAnalysis
        ).makeCursorHolder(
            CursorBuildSpec.builder().setFilter(filter).build()
        ),
        ImmutableList.of(
            "page",
            FACT_TO_COUNTRY_ON_ISO_CODE_PREFIX + "countryName"
        ),
        ImmutableList.of(
            new Object[]{"Diskussion:Sebastian Schulz", null}
        )
    );
  }

  @Test
  public void test_makeCursor_factToCountryAlwaysTrueUsingLookup()
  {
    List<JoinableClause> joinableClauses = ImmutableList.of(
        new JoinableClause(
            FACT_TO_COUNTRY_ON_ISO_CODE_PREFIX,
            LookupJoinable.wrap(countryIsoCodeToNameLookup),
            JoinType.LEFT,
            JoinConditionAnalysis.forExpression(
                "1",
                FACT_TO_COUNTRY_ON_ISO_CODE_PREFIX,
                ExprMacroTable.nil()
            )
        )
    );

    Filter filter = new SelectorDimFilter("channel", "#de.wikipedia", null).toFilter();

    JoinFilterPreAnalysis joinFilterPreAnalysis = makeDefaultConfigPreAnalysis(
        filter,
        joinableClauses,
        VirtualColumns.EMPTY
    );
    HashJoinSegmentCursorFactory cursorFactory = new HashJoinSegmentCursorFactory(
        factSegment.as(CursorFactory.class),
        null,
        joinableClauses,
        joinFilterPreAnalysis
    );
    List<String> columns = ImmutableList.of(
        "page",
        FACT_TO_COUNTRY_ON_ISO_CODE_PREFIX + "v"
    );
    CursorBuildSpec buildSpec = CursorBuildSpec.builder().setFilter(filter).build();
    JoinTestHelper.verifyCursor(
        cursorFactory.makeCursorHolder(buildSpec),
        columns,
        ImmutableList.of(
            new Object[]{"Diskussion:Sebastian Schulz", "Australia"},
            new Object[]{"Diskussion:Sebastian Schulz", "Canada"},
            new Object[]{"Diskussion:Sebastian Schulz", "Chile"},
            new Object[]{"Diskussion:Sebastian Schulz", "Germany"},
            new Object[]{"Diskussion:Sebastian Schulz", "Ecuador"},
            new Object[]{"Diskussion:Sebastian Schulz", "France"},
            new Object[]{"Diskussion:Sebastian Schulz", "United Kingdom"},
            new Object[]{"Diskussion:Sebastian Schulz", "Italy"},
            new Object[]{"Diskussion:Sebastian Schulz", "Japan"},
            new Object[]{"Diskussion:Sebastian Schulz", "Republic of Korea"},
            new Object[]{"Diskussion:Sebastian Schulz", "Mexico"},
            new Object[]{"Diskussion:Sebastian Schulz", "Norway"},
            new Object[]{"Diskussion:Sebastian Schulz", "El Salvador"},
            new Object[]{"Diskussion:Sebastian Schulz", "United States"},
            new Object[]{"Diskussion:Sebastian Schulz", "Atlantis"},
            new Object[]{"Diskussion:Sebastian Schulz", "States United"},
            new Object[]{"Diskussion:Sebastian Schulz", "Usca"},
            new Object[]{"Diskussion:Sebastian Schulz", "Fourems"}
        )
    );
  }

  @Test
  public void test_makeCursor_factToCountryAlwaysFalseUsingLookup()
  {
    List<JoinableClause> joinableClauses = ImmutableList.of(
        new JoinableClause(
            FACT_TO_COUNTRY_ON_ISO_CODE_PREFIX,
            LookupJoinable.wrap(countryIsoCodeToNameLookup),
            JoinType.LEFT,
            JoinConditionAnalysis.forExpression(
                "0",
                FACT_TO_COUNTRY_ON_ISO_CODE_PREFIX,
                ExprMacroTable.nil()
            )
        )
    );

    Filter filter = new SelectorDimFilter("channel", "#de.wikipedia", null).toFilter();

    JoinFilterPreAnalysis joinFilterPreAnalysis = makeDefaultConfigPreAnalysis(
        filter,
        joinableClauses,
        VirtualColumns.EMPTY
    );

    JoinTestHelper.verifyCursor(
        new HashJoinSegmentCursorFactory(
            factSegment.as(CursorFactory.class),
            null,
            joinableClauses,
            joinFilterPreAnalysis
        ).makeCursorHolder(
            CursorBuildSpec.builder().setFilter(filter).build()
        ),
        ImmutableList.of(
            "page",
            FACT_TO_COUNTRY_ON_ISO_CODE_PREFIX + "v"
        ),
        ImmutableList.of(
            new Object[]{"Diskussion:Sebastian Schulz", null}
        )
    );
  }

  @Test
  public void test_makeCursor_factToCountryUsingVirtualColumn()
  {
    List<JoinableClause> joinableClauses = ImmutableList.of(
        new JoinableClause(
            FACT_TO_COUNTRY_ON_ISO_CODE_PREFIX,
            new IndexedTableJoinable(countriesTable),
            JoinType.INNER,
            JoinConditionAnalysis.forExpression(
                StringUtils.format("\"%scountryIsoCode\" == virtual", FACT_TO_COUNTRY_ON_ISO_CODE_PREFIX),
                FACT_TO_COUNTRY_ON_ISO_CODE_PREFIX,
                ExprMacroTable.nil()
            )
        )
    );

    VirtualColumns virtualColumns = VirtualColumns.create(
        Collections.singletonList(
            makeExpressionVirtualColumn("concat(substring(countryIsoCode, 0, 1),'L')")
        )
    );

    JoinFilterPreAnalysis joinFilterPreAnalysis = makeDefaultConfigPreAnalysis(
        null,
        joinableClauses,
        virtualColumns
    );
    JoinTestHelper.verifyCursor(
        new HashJoinSegmentCursorFactory(
            factSegment.as(CursorFactory.class),
            null,
            joinableClauses,
            joinFilterPreAnalysis
        ).makeCursorHolder(
            CursorBuildSpec.builder().setVirtualColumns(virtualColumns).build()
        ),
        ImmutableList.of(
            "page",
            "countryIsoCode",
            "virtual",
            FACT_TO_COUNTRY_ON_ISO_CODE_PREFIX + "countryIsoCode",
            FACT_TO_COUNTRY_ON_ISO_CODE_PREFIX + "countryName"
        ),
        ImmutableList.of(
            new Object[]{"Golpe de Estado en Chile de 1973", "CL", "CL", "CL", "Chile"},
            new Object[]{"Didier Leclair", "CA", "CL", "CL", "Chile"},
            new Object[]{"Les Argonautes", "CA", "CL", "CL", "Chile"},
            new Object[]{"Sarah Michelle Gellar", "CA", "CL", "CL", "Chile"}
        )
    );
  }

  @Test
  public void test_makeCursor_factToCountryUsingVirtualColumnUsingLookup()
  {
    List<JoinableClause> joinableClauses = ImmutableList.of(
        new JoinableClause(
            FACT_TO_COUNTRY_ON_ISO_CODE_PREFIX,
            LookupJoinable.wrap(countryIsoCodeToNameLookup),
            JoinType.INNER,
            JoinConditionAnalysis.forExpression(
                StringUtils.format("\"%sk\" == virtual", FACT_TO_COUNTRY_ON_ISO_CODE_PREFIX),
                FACT_TO_COUNTRY_ON_ISO_CODE_PREFIX,
                ExprMacroTable.nil()
            )
        )
    );

    VirtualColumns virtualColumns = VirtualColumns.create(
        Collections.singletonList(
            makeExpressionVirtualColumn("concat(substring(countryIsoCode, 0, 1),'L')")
        )
    );

    JoinFilterPreAnalysis joinFilterPreAnalysis = makeDefaultConfigPreAnalysis(
        null,
        joinableClauses,
        virtualColumns
    );
    JoinTestHelper.verifyCursor(
        new HashJoinSegmentCursorFactory(
            factSegment.as(CursorFactory.class),
            null,
            joinableClauses,
            joinFilterPreAnalysis
        ).makeCursorHolder(
            CursorBuildSpec.builder().setVirtualColumns(virtualColumns).build()
        ),
        ImmutableList.of(
            "page",
            "countryIsoCode",
            "virtual",
            FACT_TO_COUNTRY_ON_ISO_CODE_PREFIX + "k",
            FACT_TO_COUNTRY_ON_ISO_CODE_PREFIX + "v"
        ),
        ImmutableList.of(
            new Object[]{"Golpe de Estado en Chile de 1973", "CL", "CL", "CL", "Chile"},
            new Object[]{"Didier Leclair", "CA", "CL", "CL", "Chile"},
            new Object[]{"Les Argonautes", "CA", "CL", "CL", "Chile"},
            new Object[]{"Sarah Michelle Gellar", "CA", "CL", "CL", "Chile"}
        )
    );
  }

  @Test
  public void test_makeCursor_factToCountryUsingExpression()
  {
    List<JoinableClause> joinableClauses = ImmutableList.of(
        new JoinableClause(
            FACT_TO_COUNTRY_ON_ISO_CODE_PREFIX,
            new IndexedTableJoinable(countriesTable),
            JoinType.INNER,
            JoinConditionAnalysis.forExpression(
                StringUtils.format(
                    "\"%scountryIsoCode\" == concat(substring(countryIsoCode, 0, 1),'L')",
                    FACT_TO_COUNTRY_ON_ISO_CODE_PREFIX
                ),
                FACT_TO_COUNTRY_ON_ISO_CODE_PREFIX,
                ExprMacroTable.nil()
            )
        )
    );

    JoinFilterPreAnalysis joinFilterPreAnalysis = makeDefaultConfigPreAnalysis(
        null,
        joinableClauses,
        VirtualColumns.EMPTY
    );
    JoinTestHelper.verifyCursor(
        new HashJoinSegmentCursorFactory(
            factSegment.as(CursorFactory.class),
            null,
            joinableClauses,
            joinFilterPreAnalysis
        ).makeCursorHolder(CursorBuildSpec.FULL_SCAN),
        ImmutableList.of(
            "page",
            "countryIsoCode",
            FACT_TO_COUNTRY_ON_ISO_CODE_PREFIX + "countryIsoCode",
            FACT_TO_COUNTRY_ON_ISO_CODE_PREFIX + "countryName"
        ),
        ImmutableList.of(
            new Object[]{"Golpe de Estado en Chile de 1973", "CL", "CL", "Chile"},
            new Object[]{"Didier Leclair", "CA", "CL", "Chile"},
            new Object[]{"Les Argonautes", "CA", "CL", "Chile"},
            new Object[]{"Sarah Michelle Gellar", "CA", "CL", "Chile"}
        )
    );
  }

  @Test
  public void test_makeCursor_factToCountryUsingExpressionUsingLookup()
  {
    List<JoinableClause> joinableClauses = ImmutableList.of(
        new JoinableClause(
            FACT_TO_COUNTRY_ON_ISO_CODE_PREFIX,
            LookupJoinable.wrap(countryIsoCodeToNameLookup),
            JoinType.INNER,
            JoinConditionAnalysis.forExpression(
                StringUtils.format(
                    "\"%sk\" == concat(substring(countryIsoCode, 0, 1),'L')",
                    FACT_TO_COUNTRY_ON_ISO_CODE_PREFIX
                ),
                FACT_TO_COUNTRY_ON_ISO_CODE_PREFIX,
                ExprMacroTable.nil()
            )
        )
    );

    JoinFilterPreAnalysis joinFilterPreAnalysis = makeDefaultConfigPreAnalysis(
        null,
        joinableClauses,
        VirtualColumns.EMPTY
    );
    JoinTestHelper.verifyCursor(
        new HashJoinSegmentCursorFactory(
            factSegment.as(CursorFactory.class),
            null,
            joinableClauses,
            joinFilterPreAnalysis
        ).makeCursorHolder(CursorBuildSpec.FULL_SCAN),
        ImmutableList.of(
            "page",
            "countryIsoCode",
            FACT_TO_COUNTRY_ON_ISO_CODE_PREFIX + "k",
            FACT_TO_COUNTRY_ON_ISO_CODE_PREFIX + "v"
        ),
        ImmutableList.of(
            new Object[]{"Golpe de Estado en Chile de 1973", "CL", "CL", "Chile"},
            new Object[]{"Didier Leclair", "CA", "CL", "Chile"},
            new Object[]{"Les Argonautes", "CA", "CL", "Chile"},
            new Object[]{"Sarah Michelle Gellar", "CA", "CL", "Chile"}
        )
    );
  }

  @Test
  public void test_makeCursor_factToRegionTheWrongWay()
  {
    // Joins using only regionIsoCode, which is wrong since they are not unique internationally.
    List<JoinableClause> joinableClauses = ImmutableList.of(
        new JoinableClause(
            FACT_TO_REGION_PREFIX,
            new IndexedTableJoinable(regionsTable),
            JoinType.LEFT,
            JoinConditionAnalysis.forExpression(
                StringUtils.format(
                    "\"%sregionIsoCode\" == regionIsoCode",
                    FACT_TO_REGION_PREFIX
                ),
                FACT_TO_REGION_PREFIX,
                ExprMacroTable.nil()
            )
        )
    );

    Filter filter = new SelectorDimFilter("regionIsoCode", "VA", null).toFilter();
    JoinFilterPreAnalysis joinFilterPreAnalysis = makeDefaultConfigPreAnalysis(
        filter,
        joinableClauses,
        VirtualColumns.EMPTY
    );
    HashJoinSegmentCursorFactory cursorFactory = new HashJoinSegmentCursorFactory(
        factSegment.as(CursorFactory.class),
        null,
        joinableClauses,
        joinFilterPreAnalysis
    );
    List<String> columns = ImmutableList.of(
        "page",
        "regionIsoCode",
        "countryIsoCode",
        FACT_TO_REGION_PREFIX + "regionName",
        FACT_TO_REGION_PREFIX + "countryIsoCode"
    );
    CursorBuildSpec buildSpec = CursorBuildSpec.builder().setFilter(filter).build();
    JoinTestHelper.verifyCursor(
        cursorFactory.makeCursorHolder(buildSpec),
        columns,
        ImmutableList.of(
            new Object[]{"Giusy Ferreri discography", "VA", "IT", "Provincia di Varese", "IT"},
            new Object[]{"Giusy Ferreri discography", "VA", "IT", "Virginia", "US"},
            new Object[]{"Roma-Bangkok", "VA", "IT", "Provincia di Varese", "IT"},
            new Object[]{"Roma-Bangkok", "VA", "IT", "Virginia", "US"},
            new Object[]{"Old Anatolian Turkish", "VA", "US", "Provincia di Varese", "IT"},
            new Object[]{"Old Anatolian Turkish", "VA", "US", "Virginia", "US"}
        )
    );
  }

  @Test
  public void test_makeCursor_errorOnNonEquiJoin()
  {
    expectedException.expect(IllegalArgumentException.class);
    expectedException.expectMessage("Cannot build hash-join matcher on non-equi-join condition: x == y");

    List<JoinableClause> joinableClauses = ImmutableList.of(
        new JoinableClause(
            FACT_TO_COUNTRY_ON_ISO_CODE_PREFIX,
            new IndexedTableJoinable(countriesTable),
            JoinType.LEFT,
            JoinConditionAnalysis.forExpression(
                "x == y",
                FACT_TO_COUNTRY_ON_ISO_CODE_PREFIX,
                ExprMacroTable.nil()
            )
        )
    );

    JoinFilterPreAnalysis joinFilterPreAnalysis = makeDefaultConfigPreAnalysis(
        null,
        joinableClauses,
        VirtualColumns.EMPTY
    );

    JoinTestHelper.readCursor(
        new HashJoinSegmentCursorFactory(
            factSegment.as(CursorFactory.class),
            null,
            joinableClauses,
            joinFilterPreAnalysis
        ).makeCursorHolder(CursorBuildSpec.FULL_SCAN),
        ImmutableList.of()
    );
  }

  @Test
  public void test_makeCursor_errorOnNonEquiJoinUsingLookup()
  {
    expectedException.expect(IllegalArgumentException.class);
    expectedException.expectMessage("Cannot join lookup with non-equi condition: x == y");

    List<JoinableClause> joinableClauses = ImmutableList.of(
        new JoinableClause(
            FACT_TO_COUNTRY_ON_ISO_CODE_PREFIX,
            LookupJoinable.wrap(countryIsoCodeToNameLookup),
            JoinType.LEFT,
            JoinConditionAnalysis.forExpression(
                "x == y",
                FACT_TO_COUNTRY_ON_ISO_CODE_PREFIX,
                ExprMacroTable.nil()
            )
        )
    );

    JoinFilterPreAnalysis joinFilterPreAnalysis = makeDefaultConfigPreAnalysis(
        null,
        joinableClauses,
        VirtualColumns.EMPTY
    );

    JoinTestHelper.readCursor(
        new HashJoinSegmentCursorFactory(
            factSegment.as(CursorFactory.class),
            null,
            joinableClauses,
            joinFilterPreAnalysis
        ).makeCursorHolder(CursorBuildSpec.FULL_SCAN),
        ImmutableList.of()
    );
  }

  @Test
  public void test_makeCursor_errorOnNonKeyBasedJoin()
  {
    expectedException.expect(IllegalArgumentException.class);
    expectedException.expectMessage("Cannot build hash-join matcher on non-key-based condition: "
                                    + "Equality{leftExpr=x, rightColumn='countryName', includeNull=false}");
    List<JoinableClause> joinableClauses = ImmutableList.of(
        new JoinableClause(
            FACT_TO_COUNTRY_ON_ISO_CODE_PREFIX,
            new IndexedTableJoinable(countriesTable),
            JoinType.LEFT,
            JoinConditionAnalysis.forExpression(
                StringUtils.format("x == \"%scountryName\"", FACT_TO_COUNTRY_ON_ISO_CODE_PREFIX),
                FACT_TO_COUNTRY_ON_ISO_CODE_PREFIX,
                ExprMacroTable.nil()
            )
        )
    );

    JoinFilterPreAnalysis joinFilterPreAnalysis = makeDefaultConfigPreAnalysis(
        null,
        joinableClauses,
        VirtualColumns.EMPTY
    );

    JoinTestHelper.readCursor(
        new HashJoinSegmentCursorFactory(
            factSegment.as(CursorFactory.class),
            null,
            joinableClauses,
            joinFilterPreAnalysis
        ).makeCursorHolder(CursorBuildSpec.FULL_SCAN),
        ImmutableList.of()
    );
  }

  @Test
  public void test_makeCursor_errorOnNonKeyBasedJoinUsingLookup()
  {
    expectedException.expect(IllegalArgumentException.class);
    expectedException.expectMessage(
        "Cannot join lookup with condition referring to non-key column: x == \"c1.countryName");
    List<JoinableClause> joinableClauses = ImmutableList.of(
        new JoinableClause(
            FACT_TO_COUNTRY_ON_ISO_CODE_PREFIX,
            LookupJoinable.wrap(countryIsoCodeToNameLookup),
            JoinType.LEFT,
            JoinConditionAnalysis.forExpression(
                StringUtils.format("x == \"%scountryName\"", FACT_TO_COUNTRY_ON_ISO_CODE_PREFIX),
                FACT_TO_COUNTRY_ON_ISO_CODE_PREFIX,
                ExprMacroTable.nil()
            )
        )
    );

    JoinFilterPreAnalysis joinFilterPreAnalysis = makeDefaultConfigPreAnalysis(
        null,
        joinableClauses,
        VirtualColumns.EMPTY
    );

    JoinTestHelper.readCursor(
        new HashJoinSegmentCursorFactory(
            factSegment.as(CursorFactory.class),
            null,
            joinableClauses,
            joinFilterPreAnalysis
        ).makeCursorHolder(CursorBuildSpec.FULL_SCAN),
        ImmutableList.of()
    );
  }

  @Test
  public void test_makeCursor_factToCountryLeft_filterExcludesAllLeftRows()
  {
    Filter originalFilter = new SelectorFilter("page", "this matches nothing");
    List<JoinableClause> joinableClauses = ImmutableList.of(factToCountryOnIsoCode(JoinType.LEFT));

    JoinFilterPreAnalysis joinFilterPreAnalysis = makeDefaultConfigPreAnalysis(
        originalFilter,
        joinableClauses,
        VirtualColumns.EMPTY
    );
    JoinTestHelper.verifyCursor(
        new HashJoinSegmentCursorFactory(
            factSegment.as(CursorFactory.class),
            null,
            joinableClauses,
            joinFilterPreAnalysis
        ).makeCursorHolder(
            CursorBuildSpec.builder().setFilter(originalFilter).build()
        ),
        ImmutableList.of(
            "page",
            "countryIsoCode",
            FACT_TO_COUNTRY_ON_ISO_CODE_PREFIX + "countryIsoCode",
            FACT_TO_COUNTRY_ON_ISO_CODE_PREFIX + "countryName",
            FACT_TO_COUNTRY_ON_ISO_CODE_PREFIX + "countryNumber"
        ),
        ImmutableList.of()
    );
  }

  @Test
  public void test_makeCursor_factToCountryLeft_filterExcludesAllLeftRowsUsingLookup()
  {
    Filter originalFilter = new SelectorFilter("page", "this matches nothing");
    List<JoinableClause> joinableClauses = ImmutableList.of(factToCountryNameUsingIsoCodeLookup(JoinType.LEFT));
    JoinFilterPreAnalysis joinFilterPreAnalysis = makeDefaultConfigPreAnalysis(
        originalFilter,
        joinableClauses,
        VirtualColumns.EMPTY
    );
    JoinTestHelper.verifyCursor(
        new HashJoinSegmentCursorFactory(
            factSegment.as(CursorFactory.class),
            null,
            joinableClauses,
            joinFilterPreAnalysis
        ).makeCursorHolder(
            CursorBuildSpec.builder().setFilter(originalFilter).build()
        ),
        ImmutableList.of(
            "page",
            "countryIsoCode",
            FACT_TO_COUNTRY_ON_ISO_CODE_PREFIX + "k",
            FACT_TO_COUNTRY_ON_ISO_CODE_PREFIX + "v"
        ),
        ImmutableList.of()
    );
  }

  @Test
  public void test_makeCursor_originalFilterDoesNotMatchPreAnalysis_shouldThrowISE()
  {
    List<JoinableClause> joinableClauses = ImmutableList.of(factToCountryOnIsoCode(JoinType.LEFT));
    Filter filter = new SelectorFilter("page", "this matches nothing");
    JoinFilterPreAnalysis joinFilterPreAnalysis = makeDefaultConfigPreAnalysis(
        filter,
        joinableClauses,
        VirtualColumns.EMPTY
    );

    new HashJoinSegmentCursorFactory(
        factSegment.as(CursorFactory.class),
        null,
        joinableClauses,
        joinFilterPreAnalysis
    ).makeCursorHolder(
        CursorBuildSpec.builder().setFilter(filter).build()
    );
  }

  @Test
  public void test_makeCursor_factToCountryLeftWithBaseFilter()
  {
    final Filter baseFilter = Filters.or(Arrays.asList(
        new SelectorDimFilter("countryIsoCode", "CA", null).toFilter(),
        new SelectorDimFilter("countryIsoCode", "MatchNothing", null).toFilter()
    ));

    List<JoinableClause> joinableClauses = ImmutableList.of(factToCountryOnIsoCode(JoinType.LEFT));

    JoinFilterPreAnalysis joinFilterPreAnalysis = makeDefaultConfigPreAnalysis(
        baseFilter,
        joinableClauses,
        VirtualColumns.EMPTY
    );
    JoinTestHelper.verifyCursor(
        new HashJoinSegmentCursorFactory(
            factSegment.as(CursorFactory.class),
            baseFilter,
            joinableClauses,
            joinFilterPreAnalysis
        ).makeCursorHolder(CursorBuildSpec.FULL_SCAN),
        ImmutableList.of(
            "page",
            "countryIsoCode",
            FACT_TO_COUNTRY_ON_ISO_CODE_PREFIX + "countryIsoCode",
            FACT_TO_COUNTRY_ON_ISO_CODE_PREFIX + "countryName",
            FACT_TO_COUNTRY_ON_ISO_CODE_PREFIX + "countryNumber"
        ),
        ImmutableList.of(
            new Object[]{"Didier Leclair", "CA", "CA", "Canada", 1L},
            new Object[]{"Les Argonautes", "CA", "CA", "Canada", 1L},
            new Object[]{"Sarah Michelle Gellar", "CA", "CA", "Canada", 1L},
            new Object[]{"Orange Soda", "MatchNothing", null, null, NULL_COUNTRY}
        )
    );
  }

  @Test
  public void test_makeCursor_factToCountryInnerWithBaseFilter()
  {
    final Filter baseFilter = Filters.or(Arrays.asList(
        new SelectorDimFilter("countryIsoCode", "CA", null).toFilter(),
        new SelectorDimFilter("countryIsoCode", "MatchNothing", null).toFilter()
    ));

    List<JoinableClause> joinableClauses = ImmutableList.of(factToCountryOnIsoCode(JoinType.INNER));
    JoinFilterPreAnalysis joinFilterPreAnalysis = makeDefaultConfigPreAnalysis(
        baseFilter,
        joinableClauses,
        VirtualColumns.EMPTY
    );
    JoinTestHelper.verifyCursor(
        new HashJoinSegmentCursorFactory(
            factSegment.as(CursorFactory.class),
            baseFilter,
            joinableClauses,
            joinFilterPreAnalysis
        ).makeCursorHolder(CursorBuildSpec.FULL_SCAN),
        ImmutableList.of(
            "page",
            "countryIsoCode",
            FACT_TO_COUNTRY_ON_ISO_CODE_PREFIX + "countryIsoCode",
            FACT_TO_COUNTRY_ON_ISO_CODE_PREFIX + "countryName",
            FACT_TO_COUNTRY_ON_ISO_CODE_PREFIX + "countryNumber"
        ),
        ImmutableList.of(
            new Object[]{"Didier Leclair", "CA", "CA", "Canada", 1L},
            new Object[]{"Les Argonautes", "CA", "CA", "Canada", 1L},
            new Object[]{"Sarah Michelle Gellar", "CA", "CA", "Canada", 1L}
        )
    );
  }

  @Test
  public void test_makeCursor_factToCountryRightWithBaseFilter()
  {
    final Filter baseFilter = Filters.or(Arrays.asList(
        new SelectorDimFilter("countryIsoCode", "CA", null).toFilter(),
        new SelectorDimFilter("countryIsoCode", "MatchNothing", null).toFilter()
    ));

    List<JoinableClause> joinableClauses = ImmutableList.of(factToCountryOnIsoCode(JoinType.RIGHT));
    JoinFilterPreAnalysis joinFilterPreAnalysis = makeDefaultConfigPreAnalysis(
        baseFilter,
        joinableClauses,
        VirtualColumns.EMPTY
    );
    HashJoinSegmentCursorFactory cursorFactory = new HashJoinSegmentCursorFactory(
        factSegment.as(CursorFactory.class),
        baseFilter,
        joinableClauses,
        joinFilterPreAnalysis
    );
    List<String> columns = ImmutableList.of(
        "page",
        "countryIsoCode",
        FACT_TO_COUNTRY_ON_ISO_CODE_PREFIX + "countryIsoCode",
        FACT_TO_COUNTRY_ON_ISO_CODE_PREFIX + "countryName",
        FACT_TO_COUNTRY_ON_ISO_CODE_PREFIX + "countryNumber"
    );
    JoinTestHelper.verifyCursor(
        cursorFactory.makeCursorHolder(CursorBuildSpec.FULL_SCAN),
        columns,
        ImmutableList.of(
            new Object[]{"Didier Leclair", "CA", "CA", "Canada", 1L},
            new Object[]{"Les Argonautes", "CA", "CA", "Canada", 1L},
            new Object[]{"Sarah Michelle Gellar", "CA", "CA", "Canada", 1L},
            new Object[]{null, null, "AU", "Australia", 0L},
            new Object[]{null, null, "CL", "Chile", 2L},
            new Object[]{null, null, "DE", "Germany", 3L},
            new Object[]{null, null, "EC", "Ecuador", 4L},
            new Object[]{null, null, "FR", "France", 5L},
            new Object[]{null, null, "GB", "United Kingdom", 6L},
            new Object[]{null, null, "IT", "Italy", 7L},
            new Object[]{null, null, "JP", "Japan", 8L},
            new Object[]{null, null, "KR", "Republic of Korea", 9L},
            new Object[]{null, null, "MX", "Mexico", 10L},
            new Object[]{null, null, "NO", "Norway", 11L},
            new Object[]{null, null, "SV", "El Salvador", 12L},
            new Object[]{null, null, "US", "United States", 13L},
            new Object[]{null, null, "AX", "Atlantis", 14L},
            new Object[]{null, null, "SU", "States United", 15L},
            new Object[]{null, null, "USCA", "Usca", 16L},
            new Object[]{null, null, "MMMM", "Fourems", 205L}
        )
    );
  }

  @Test
  public void test_makeCursor_factToCountryFullWithBaseFilter()
  {
    final Filter baseFilter = Filters.or(Arrays.asList(
        new SelectorDimFilter("countryIsoCode", "CA", null).toFilter(),
        new SelectorDimFilter("countryIsoCode", "MatchNothing", null).toFilter()
    ));

    List<JoinableClause> joinableClauses = ImmutableList.of(factToCountryOnIsoCode(JoinType.FULL));
    JoinFilterPreAnalysis joinFilterPreAnalysis = makeDefaultConfigPreAnalysis(
        baseFilter,
        joinableClauses,
        VirtualColumns.EMPTY
    );
    HashJoinSegmentCursorFactory cursorFactory = new HashJoinSegmentCursorFactory(
        factSegment.as(CursorFactory.class),
        baseFilter,
        joinableClauses,
        joinFilterPreAnalysis
    );
    List<String> columns = ImmutableList.of(
        "page",
        "countryIsoCode",
        FACT_TO_COUNTRY_ON_ISO_CODE_PREFIX + "countryIsoCode",
        FACT_TO_COUNTRY_ON_ISO_CODE_PREFIX + "countryName",
        FACT_TO_COUNTRY_ON_ISO_CODE_PREFIX + "countryNumber"
    );
    JoinTestHelper.verifyCursor(
        cursorFactory.makeCursorHolder(CursorBuildSpec.FULL_SCAN),
        columns,
        ImmutableList.of(
            new Object[]{"Didier Leclair", "CA", "CA", "Canada", 1L},
            new Object[]{"Les Argonautes", "CA", "CA", "Canada", 1L},
            new Object[]{"Sarah Michelle Gellar", "CA", "CA", "Canada", 1L},
            new Object[]{"Orange Soda", "MatchNothing", null, null, null},
            new Object[]{null, null, "AU", "Australia", 0L},
            new Object[]{null, null, "CL", "Chile", 2L},
            new Object[]{null, null, "DE", "Germany", 3L},
            new Object[]{null, null, "EC", "Ecuador", 4L},
            new Object[]{null, null, "FR", "France", 5L},
            new Object[]{null, null, "GB", "United Kingdom", 6L},
            new Object[]{null, null, "IT", "Italy", 7L},
            new Object[]{null, null, "JP", "Japan", 8L},
            new Object[]{null, null, "KR", "Republic of Korea", 9L},
            new Object[]{null, null, "MX", "Mexico", 10L},
            new Object[]{null, null, "NO", "Norway", 11L},
            new Object[]{null, null, "SV", "El Salvador", 12L},
            new Object[]{null, null, "US", "United States", 13L},
            new Object[]{null, null, "AX", "Atlantis", 14L},
            new Object[]{null, null, "SU", "States United", 15L},
            new Object[]{null, null, "USCA", "Usca", 16L},
            new Object[]{null, null, "MMMM", "Fourems", 205L}
        )
    );
  }

  @Test
  public void test_hasBuiltInFiltersForSingleJoinableClauseWithVariousJoinTypes()
  {
    Assert.assertFalse(makeFactToCountrySegment(JoinType.INNER).as(TopNOptimizationInspector.class).areAllDictionaryIdsPresent());
    Assert.assertTrue(makeFactToCountrySegment(JoinType.LEFT).as(TopNOptimizationInspector.class).areAllDictionaryIdsPresent());
    Assert.assertFalse(makeFactToCountrySegment(JoinType.RIGHT).as(TopNOptimizationInspector.class).areAllDictionaryIdsPresent());
    Assert.assertTrue(makeFactToCountrySegment(JoinType.FULL).as(TopNOptimizationInspector.class).areAllDictionaryIdsPresent());
    // cross join
    HashJoinSegment segment = new HashJoinSegment(
        ReferenceCountedSegmentProvider.wrapRootGenerationSegment(factSegment).acquireReference().orElseThrow(),
        null,
        ImmutableList.of(
            new JoinableClause(
                FACT_TO_COUNTRY_ON_ISO_CODE_PREFIX,
                new IndexedTableJoinable(countriesTable),
                JoinType.INNER,
                JoinConditionAnalysis.forExpression(
                    "'true'",
                    FACT_TO_COUNTRY_ON_ISO_CODE_PREFIX,
                    ExprMacroTable.nil()
                )
            )
        ),
        null,
        () -> {}
    );
    TopNOptimizationInspector inspector = segment.as(TopNOptimizationInspector.class);
    Assert.assertTrue(inspector.areAllDictionaryIdsPresent());
  }

  @Test
  public void test_hasBuiltInFiltersForConvertedJoin()
  {
    final HashJoinSegment segment = new HashJoinSegment(
        ReferenceCountedSegmentProvider.wrapRootGenerationSegment(factSegment).acquireReference().orElseThrow(),
        new InDimFilter("dim", ImmutableSet.of("foo", "bar")),
        ImmutableList.of(),
        null,
        () -> {}
    );
    final TopNOptimizationInspector inspector = segment.as(TopNOptimizationInspector.class);
    Assert.assertFalse(inspector.areAllDictionaryIdsPresent());
  }

  @Test
  public void test_hasBuiltInFiltersForMultipleJoinableClausesWithVariousJoinTypes()
  {
    final HashJoinSegment segment = new HashJoinSegment(
        ReferenceCountedSegmentProvider.wrapRootGenerationSegment(factSegment).acquireReference().orElseThrow(),
        null,
        ImmutableList.of(
            factToRegion(JoinType.INNER),
            regionToCountry(JoinType.LEFT)
        ),
        null,
        () -> {}
    );
    Assert.assertFalse(segment.as(TopNOptimizationInspector.class).areAllDictionaryIdsPresent());

    final HashJoinSegment segment2 = new HashJoinSegment(
        ReferenceCountedSegmentProvider.wrapRootGenerationSegment(factSegment).acquireReference().orElseThrow(),
        null,
        ImmutableList.of(
            factToRegion(JoinType.RIGHT),
            regionToCountry(JoinType.INNER),
            factToCountryOnNumber(JoinType.FULL)
        ),
        null,
        () -> {}
    );
    Assert.assertFalse(segment2.as(TopNOptimizationInspector.class).areAllDictionaryIdsPresent());

    final HashJoinSegment segment3 = new HashJoinSegment(
        ReferenceCountedSegmentProvider.wrapRootGenerationSegment(factSegment).acquireReference().orElseThrow(),
        null,
        ImmutableList.of(
            factToRegion(JoinType.LEFT),
            regionToCountry(JoinType.LEFT)
        ),
        null,
        () -> {}
    );
    Assert.assertTrue(segment3.as(TopNOptimizationInspector.class).areAllDictionaryIdsPresent());

    final HashJoinSegment segment4 = new HashJoinSegment(
        ReferenceCountedSegmentProvider.wrapRootGenerationSegment(factSegment).acquireReference().orElseThrow(),
        null,
        ImmutableList.of(
            factToRegion(JoinType.LEFT),
            new JoinableClause(
                FACT_TO_COUNTRY_ON_ISO_CODE_PREFIX,
                new IndexedTableJoinable(countriesTable),
                JoinType.INNER,
                JoinConditionAnalysis.forExpression(
                    "'true'",
                    FACT_TO_COUNTRY_ON_ISO_CODE_PREFIX,
                    ExprMacroTable.nil()
                )
            )
        ),
        null,
        () -> {}
    );
    Assert.assertTrue(segment4.as(TopNOptimizationInspector.class).areAllDictionaryIdsPresent());
  }
}
