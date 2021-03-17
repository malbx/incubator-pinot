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
package org.apache.pinot.core.segment.index.metadata;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.lang.reflect.Field;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.apache.commons.configuration.PropertiesConfiguration;
import org.apache.commons.lang3.StringEscapeUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.pinot.common.metadata.segment.ColumnPartitionMetadata;
import org.apache.pinot.core.data.partition.PartitionFunction;
import org.apache.pinot.core.data.partition.PartitionFunctionFactory;
import org.apache.pinot.core.segment.creator.TextIndexType;
import org.apache.pinot.core.segment.creator.impl.V1Constants;
import org.apache.pinot.spi.data.DateTimeFieldSpec;
import org.apache.pinot.spi.data.DimensionFieldSpec;
import org.apache.pinot.spi.data.FieldSpec;
import org.apache.pinot.spi.data.FieldSpec.DataType;
import org.apache.pinot.spi.data.FieldSpec.FieldType;
import org.apache.pinot.spi.data.MetricFieldSpec;
import org.apache.pinot.spi.data.TimeFieldSpec;
import org.apache.pinot.spi.data.TimeGranularitySpec;
import org.apache.pinot.spi.utils.BytesUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.apache.pinot.core.segment.creator.impl.V1Constants.MetadataKeys.Column.*;
import static org.apache.pinot.core.segment.creator.impl.V1Constants.MetadataKeys.Segment.SEGMENT_PADDING_CHARACTER;
import static org.apache.pinot.core.segment.creator.impl.V1Constants.MetadataKeys.Segment.TIME_UNIT;


public class ColumnMetadata {
  private static final Logger LOGGER = LoggerFactory.getLogger(ColumnMetadata.class);

  private final FieldSpec fieldSpec;
  private final String columnName;
  private final int cardinality;
  private final int totalDocs;
  private final DataType dataType;
  private final int bitsPerElement;
  private final int columnMaxLength;
  private final FieldType fieldType;
  private final boolean isSorted;
  @JsonProperty
  private final boolean containsNulls;
  @JsonProperty
  private final boolean hasDictionary;
  @JsonProperty
  private final boolean hasInvertedIndex;
  private final boolean hasFSTIndex;
  private final boolean isSingleValue;
  private final boolean isVirtual;
  private final int maxNumberOfMultiValues;
  private final int totalNumberOfEntries;
  private final boolean isAutoGenerated;
  private final String defaultNullValueString;
  private final TimeUnit timeUnit;
  private final char paddingCharacter;
  private final Comparable minValue;
  private final Comparable maxValue;
  private final PartitionFunction partitionFunction;
  private final int numPartitions;
  private final Set<Integer> _partitions;
  private final String dateTimeFormat;
  private final String dateTimeGranularity;
  private final TextIndexType textIndexType;

  public static ColumnMetadata fromPropertiesConfiguration(String column, PropertiesConfiguration config) {
    Builder builder = new Builder();

    builder.setColumnName(column);
    builder.setCardinality(config.getInt(getKeyFor(column, CARDINALITY)));
    int totalDocs = config.getInt(getKeyFor(column, TOTAL_DOCS));
    builder.setTotalDocs(totalDocs);
    DataType dataType = DataType.valueOf(config.getString(getKeyFor(column, DATA_TYPE)).toUpperCase());
    builder.setDataType(dataType);
    builder.setBitsPerElement(config.getInt(getKeyFor(column, BITS_PER_ELEMENT)));
    builder.setColumnMaxLength(config.getInt(getKeyFor(column, DICTIONARY_ELEMENT_SIZE)));
    builder.setFieldType(FieldType.valueOf(config.getString(getKeyFor(column, COLUMN_TYPE)).toUpperCase()));
    builder.setIsSorted(config.getBoolean(getKeyFor(column, IS_SORTED)));
    builder.setContainsNulls(config.getBoolean(getKeyFor(column, HAS_NULL_VALUE)));
    builder.setHasDictionary(config.getBoolean(getKeyFor(column, HAS_DICTIONARY), true));
    builder.setHasInvertedIndex(config.getBoolean(getKeyFor(column, HAS_INVERTED_INDEX)));
    builder.setHasFSTIndex(config.getBoolean(getKeyFor(column, HAS_FST_INDEX), false));
    builder.setSingleValue(config.getBoolean(getKeyFor(column, IS_SINGLE_VALUED)));
    builder.setMaxNumberOfMultiValues(config.getInt(getKeyFor(column, MAX_MULTI_VALUE_ELEMENTS)));
    builder.setTotalNumberOfEntries(config.getInt(getKeyFor(column, TOTAL_NUMBER_OF_ENTRIES)));
    builder.setAutoGenerated(config.getBoolean(getKeyFor(column, IS_AUTO_GENERATED), false));
    builder.setDefaultNullValueString(config.getString(getKeyFor(column, DEFAULT_NULL_VALUE), null));
    builder.setTimeUnit(TimeUnit.valueOf(config.getString(TIME_UNIT, "DAYS").toUpperCase()));
    builder.setTextIndexType(config.getString(getKeyFor(column, TEXT_INDEX_TYPE), TextIndexType.NONE.name()));

    char paddingCharacter = V1Constants.Str.LEGACY_STRING_PAD_CHAR;
    if (config.containsKey(SEGMENT_PADDING_CHARACTER)) {
      String padding = config.getString(SEGMENT_PADDING_CHARACTER);
      paddingCharacter = StringEscapeUtils.unescapeJava(padding).charAt(0);
    }
    builder.setPaddingCharacter(paddingCharacter);

    String dateTimeFormat = config.getString(getKeyFor(column, DATETIME_FORMAT), null);
    if (dateTimeFormat != null) {
      builder.setDateTimeFormat(dateTimeFormat);
    }

    String dateTimeGranularity = config.getString(getKeyFor(column, DATETIME_GRANULARITY), null);
    if (dateTimeGranularity != null) {
      builder.setDateTimeGranularity(dateTimeGranularity);
    }

    // Set min/max value if available
    // NOTE: Use getProperty() instead of getString() to avoid variable substitution ('${anotherKey}'), which can cause
    //       problem for special values such as '$${' where the first '$' is identified as escape character.
    // TODO: Use getProperty() for other properties as well to avoid the overhead of variable substitution
    String minString = (String) config.getProperty(getKeyFor(column, MIN_VALUE));
    String maxString = (String) config.getProperty(getKeyFor(column, MAX_VALUE));
    if (minString != null && maxString != null) {
      switch (dataType) {
        case INT:
          builder.setMinValue(Integer.valueOf(minString));
          builder.setMaxValue(Integer.valueOf(maxString));
          break;
        case LONG:
          builder.setMinValue(Long.valueOf(minString));
          builder.setMaxValue(Long.valueOf(maxString));
          break;
        case FLOAT:
          builder.setMinValue(Float.valueOf(minString));
          builder.setMaxValue(Float.valueOf(maxString));
          break;
        case DOUBLE:
          builder.setMinValue(Double.valueOf(minString));
          builder.setMaxValue(Double.valueOf(maxString));
          break;
        case STRING:
          builder.setMinValue(minString);
          builder.setMaxValue(maxString);
          break;
        case BYTES:
          builder.setMinValue(BytesUtils.toByteArray(minString));
          builder.setMaxValue(BytesUtils.toByteArray(maxString));
          break;
        default:
          throw new IllegalStateException("Unsupported data type: " + dataType + " for column: " + column);
      }
    }

    String partitionFunctionName =
        config.getString(getKeyFor(column, V1Constants.MetadataKeys.Column.PARTITION_FUNCTION));
    if (partitionFunctionName != null) {
      int numPartitions = config.getInt(getKeyFor(column, V1Constants.MetadataKeys.Column.NUM_PARTITIONS));
      PartitionFunction partitionFunction =
          PartitionFunctionFactory.getPartitionFunction(partitionFunctionName, numPartitions);
      builder.setPartitionFunction(partitionFunction);
      builder.setNumPartitions(numPartitions);
      builder.setPartitions(ColumnPartitionMetadata
          .extractPartitions(config.getList(getKeyFor(column, V1Constants.MetadataKeys.Column.PARTITION_VALUES))));
    }

    return builder.build();
  }

  public PartitionFunction getPartitionFunction() {
    return partitionFunction;
  }

  public int getNumPartitions() {
    return numPartitions;
  }

  public Set<Integer> getPartitions() {
    return _partitions;
  }

  public static class Builder {
    private String columnName;
    private int cardinality;
    private int totalDocs;
    private DataType dataType;
    private int bitsPerElement;
    private int columnMaxLength;
    private FieldType fieldType;
    private boolean isSorted;
    private boolean containsNulls;
    private boolean hasDictionary;
    private boolean hasInvertedIndex;
    private boolean hasFSTIndex;
    private boolean isSingleValue;
    private boolean isVirtual;
    private int maxNumberOfMultiValues;
    private int totalNumberOfEntries;
    private boolean isAutoGenerated;
    private String defaultNullValueString;
    private TimeUnit timeUnit;
    private char paddingCharacter;
    private Comparable minValue;
    private Comparable maxValue;
    private PartitionFunction partitionFunction;
    private int numPartitions;
    private Set<Integer> _partitions;
    private String dateTimeFormat;
    private String dateTimeGranularity;
    private String textIndexType = TextIndexType.NONE.name();

    public Builder setColumnName(String columnName) {
      this.columnName = columnName;
      return this;
    }

    public Builder setCardinality(int cardinality) {
      this.cardinality = cardinality;
      return this;
    }

    public Builder setTotalDocs(int totalDocs) {
      this.totalDocs = totalDocs;
      return this;
    }

    public Builder setDataType(DataType dataType) {
      this.dataType = dataType.getStoredType();
      return this;
    }

    public Builder setBitsPerElement(int bitsPerElement) {
      this.bitsPerElement = bitsPerElement;
      return this;
    }

    public Builder setColumnMaxLength(int columnMaxLength) {
      this.columnMaxLength = columnMaxLength;
      return this;
    }

    public Builder setFieldType(FieldType fieldType) {
      this.fieldType = fieldType;
      return this;
    }

    public Builder setIsSorted(boolean isSorted) {
      this.isSorted = isSorted;
      return this;
    }

    public Builder setContainsNulls(boolean containsNulls) {
      this.containsNulls = containsNulls;
      return this;
    }

    public Builder setHasDictionary(boolean hasDictionary) {
      this.hasDictionary = hasDictionary;
      return this;
    }

    public Builder setHasFSTIndex(boolean hasFSTIndex) {
      this.hasFSTIndex = hasFSTIndex;
      return this;
    }

    public Builder setHasInvertedIndex(boolean hasInvertedIndex) {
      this.hasInvertedIndex = hasInvertedIndex;
      return this;
    }

    public Builder setSingleValue(boolean singleValue) {
      this.isSingleValue = singleValue;
      return this;
    }

    public Builder setMaxNumberOfMultiValues(int maxNumberOfMultiValues) {
      this.maxNumberOfMultiValues = maxNumberOfMultiValues;
      return this;
    }

    public Builder setTotalNumberOfEntries(int totalNumberOfEntries) {
      this.totalNumberOfEntries = totalNumberOfEntries;
      return this;
    }

    public Builder setAutoGenerated(boolean isAutoGenerated) {
      this.isAutoGenerated = isAutoGenerated;
      return this;
    }

    public Builder setVirtual(boolean isVirtual) {
      this.isVirtual = isVirtual;
      return this;
    }

    public Builder setDefaultNullValueString(String defaultNullValueString) {
      this.defaultNullValueString = defaultNullValueString;
      return this;
    }

    public Builder setTimeUnit(TimeUnit timeUnit) {
      this.timeUnit = timeUnit;
      return this;
    }

    public Builder setPaddingCharacter(char paddingCharacter) {
      this.paddingCharacter = paddingCharacter;
      return this;
    }

    public Builder setMinValue(Comparable minValue) {
      this.minValue = minValue;
      return this;
    }

    public Builder setMaxValue(Comparable maxValue) {
      this.maxValue = maxValue;
      return this;
    }

    public Builder setPartitionFunction(PartitionFunction partitionFunction) {
      this.partitionFunction = partitionFunction;
      return this;
    }

    public void setNumPartitions(int numPartitions) {
      this.numPartitions = numPartitions;
    }

    public Builder setPartitions(Set<Integer> partitions) {
      _partitions = partitions;
      return this;
    }

    public Builder setDateTimeFormat(String dateTimeFormat) {
      this.dateTimeFormat = dateTimeFormat;
      return this;
    }

    public Builder setDateTimeGranularity(String dateTimeGranularity) {
      this.dateTimeGranularity = dateTimeGranularity;
      return this;
    }

    public Builder setTextIndexType(String textIndexType) {
      this.textIndexType = textIndexType;
      return this;
    }

    public ColumnMetadata build() {
      return new ColumnMetadata(columnName, cardinality, totalDocs, dataType, bitsPerElement, columnMaxLength,
          fieldType, isSorted, containsNulls, hasDictionary, hasInvertedIndex, isSingleValue, maxNumberOfMultiValues,
          totalNumberOfEntries, isAutoGenerated, isVirtual, defaultNullValueString, timeUnit, paddingCharacter,
          minValue, maxValue, partitionFunction, numPartitions, _partitions, dateTimeFormat, dateTimeGranularity,
          hasFSTIndex, TextIndexType.valueOf(textIndexType));
    }
  }

  private ColumnMetadata(String columnName, int cardinality, int totalDocs, DataType dataType, int bitsPerElement,
      int columnMaxLength, FieldType fieldType, boolean isSorted, boolean hasNulls, boolean hasDictionary,
      boolean hasInvertedIndex, boolean isSingleValue, int maxNumberOfMultiValues, int totalNumberOfEntries,
      boolean isAutoGenerated, boolean isVirtual, String defaultNullValueString, TimeUnit timeUnit,
      char paddingCharacter, Comparable minValue, Comparable maxValue, PartitionFunction partitionFunction,
      int numPartitions, Set<Integer> partitions, String dateTimeFormat, String dateTimeGranularity,
      boolean hasFSTIndex, TextIndexType textIndexType) {
    this.columnName = columnName;
    this.cardinality = cardinality;
    this.totalDocs = totalDocs;
    this.dataType = dataType;
    this.bitsPerElement = bitsPerElement;
    this.columnMaxLength = columnMaxLength;
    this.fieldType = fieldType;
    this.isSorted = isSorted;
    this.containsNulls = hasNulls;
    this.hasDictionary = hasDictionary;
    this.hasInvertedIndex = hasInvertedIndex;
    this.hasFSTIndex = hasFSTIndex;
    this.isSingleValue = isSingleValue;
    this.maxNumberOfMultiValues = maxNumberOfMultiValues;
    this.totalNumberOfEntries = totalNumberOfEntries;
    this.isAutoGenerated = isAutoGenerated;
    this.isVirtual = isVirtual;
    this.defaultNullValueString = defaultNullValueString;
    this.timeUnit = timeUnit;
    this.paddingCharacter = paddingCharacter;
    this.minValue = minValue;
    this.maxValue = maxValue;
    this.partitionFunction = partitionFunction;
    this.numPartitions = numPartitions;
    _partitions = partitions;
    this.dateTimeFormat = dateTimeFormat;
    this.dateTimeGranularity = dateTimeGranularity;
    this.textIndexType = textIndexType;

    switch (fieldType) {
      case DIMENSION:
        this.fieldSpec = new DimensionFieldSpec(columnName, dataType, isSingleValue);
        break;
      case METRIC:
        this.fieldSpec = new MetricFieldSpec(columnName, dataType);
        break;
      case TIME:
        this.fieldSpec = new TimeFieldSpec(new TimeGranularitySpec(dataType, timeUnit, columnName));
        break;
      case DATE_TIME:
        this.fieldSpec = new DateTimeFieldSpec(columnName, dataType, dateTimeFormat, dateTimeGranularity);
        break;
      default:
        throw new RuntimeException("Unsupported field type: " + fieldType);
    }
  }

  public String getColumnName() {
    return columnName;
  }

  /**
   * When a realtime segment has no-dictionary columns, the cardinality for those columns will be
   * set to Constants.UNKNOWN_CARDINALITY
   *
   * @return The cardinality of the column.
   */
  public int getCardinality() {
    return cardinality;
  }

  public int getTotalDocs() {
    return totalDocs;
  }

  public DataType getDataType() {
    return dataType;
  }

  public int getBitsPerElement() {
    return bitsPerElement;
  }

  public int getColumnMaxLength() {
    return columnMaxLength;
  }

  public FieldType getFieldType() {
    return fieldType;
  }

  public boolean isSorted() {
    return isSorted;
  }

  public boolean hasNulls() {
    return containsNulls;
  }

  public boolean hasDictionary() {
    return hasDictionary;
  }

  public boolean hasInvertedIndex() {
    return hasInvertedIndex;
  }

  public boolean hasFSTIndex() {
    return hasFSTIndex;
  }

  public boolean isSingleValue() {
    return isSingleValue;
  }

  public int getMaxNumberOfMultiValues() {
    return maxNumberOfMultiValues;
  }

  public int getTotalNumberOfEntries() {
    return totalNumberOfEntries;
  }

  public boolean isAutoGenerated() {
    return isAutoGenerated;
  }

  public boolean isVirtual() {
    return isVirtual;
  }

  public String getDefaultNullValueString() {
    return defaultNullValueString;
  }

  public TimeUnit getTimeUnit() {
    return timeUnit;
  }

  public char getPaddingCharacter() {
    return paddingCharacter;
  }

  public FieldSpec getFieldSpec() {
    return fieldSpec;
  }

  public Comparable getMinValue() {
    return minValue;
  }

  public Comparable getMaxValue() {
    return maxValue;
  }

  public String getDateTimeFormat() {
    return dateTimeFormat;
  }

  public String getDateTimeGranularity() {
    return dateTimeGranularity;
  }

  public TextIndexType getTextIndexType() {
    return textIndexType;
  }

  @Override
  public String toString() {
    final StringBuilder result = new StringBuilder();
    final String newLine = System.getProperty("line.separator");

    result.append(this.getClass().getName());
    result.append(" Object {");
    result.append(newLine);

    //determine fields declared in this class only (no fields of superclass)
    final Field[] fields = this.getClass().getDeclaredFields();

    //print field names paired with their values
    for (final Field field : fields) {
      result.append("  ");
      try {
        result.append(field.getName());
        result.append(": ");
        //requires access to private field:
        result.append(field.get(this));
      } catch (final IllegalAccessException ex) {
        if (LOGGER.isErrorEnabled()) {
          LOGGER.error("Unable to access field " + field, ex);
        }
        result.append("[ERROR]");
      }
      result.append(newLine);
    }
    result.append("}");

    return result.toString();
  }

  @Override
  public boolean equals(Object object) {
    if (this == object) {
      return true;
    }
    if (object instanceof ColumnMetadata) {
      ColumnMetadata columnMetadata = (ColumnMetadata) object;
      return getColumnName() == columnMetadata.getColumnName() && getCardinality() == columnMetadata.getCardinality()
          && getTotalDocs() == columnMetadata.getTotalDocs() && getDataType().equals(columnMetadata.getDataType())
          && getBitsPerElement() == columnMetadata.getBitsPerElement() && getFieldSpec()
          .equals(columnMetadata.getFieldSpec()) && isSorted() == columnMetadata.isSorted()
          && hasNulls() == columnMetadata.hasNulls() && hasDictionary() == columnMetadata.hasDictionary()
          && hasInvertedIndex() == columnMetadata.hasInvertedIndex() && isSingleValue() == columnMetadata
          .isSingleValue() && isVirtual() == columnMetadata.isVirtual() && getMaxNumberOfMultiValues() == columnMetadata
          .getMaxNumberOfMultiValues() && getTotalNumberOfEntries() == columnMetadata.getTotalNumberOfEntries()
          && isAutoGenerated() == columnMetadata.isAutoGenerated() && StringUtils
          .equals(getDefaultNullValueString(), columnMetadata.getDefaultNullValueString())
          && getTimeUnit() == (columnMetadata.getTimeUnit()) && getPaddingCharacter() == columnMetadata
          .getPaddingCharacter() && minValue == (columnMetadata.getMinValue()) && maxValue == (columnMetadata
          .getMaxValue()) && getPartitionFunction() == (columnMetadata.getPartitionFunction())
          && getNumPartitions() == columnMetadata.getNumPartitions() && getPartitions() == (columnMetadata
          .getPartitions()) && getDateTimeFormat() == (columnMetadata.getDateTimeFormat())
          && getDateTimeGranularity() == (columnMetadata.getDateTimeGranularity()) && hasFSTIndex() == columnMetadata
          .hasFSTIndex() && getTextIndexType().equals(columnMetadata.getTextIndexType());
    }
    return false;
  }
}
