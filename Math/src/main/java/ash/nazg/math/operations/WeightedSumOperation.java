/**
 * Copyright (C) 2020 Locomizer team and Contributors
 * This project uses New BSD license with do no evil clause. For full text, check the LICENSE file in the root directory.
 */
package ash.nazg.math.operations;

import ash.nazg.config.InvalidConfigValueException;
import ash.nazg.config.tdl.Description;
import ash.nazg.config.tdl.TaskDescriptionLanguage;
import ash.nazg.spark.Operation;
import com.opencsv.CSVParser;
import com.opencsv.CSVParserBuilder;
import com.opencsv.CSVWriter;
import org.apache.hadoop.io.Text;
import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaRDDLike;
import scala.Tuple2;

import java.io.Serializable;
import java.io.StringWriter;
import java.util.*;
import java.util.stream.Collectors;

@SuppressWarnings("unused")
public class WeightedSumOperation extends Operation {
    @Description("A column with the Long count")
    public static final String DS_COUNT_COL = "count.col";
    @Description("A column with the Double value")
    public static final String DS_VALUE_COL = "value.col";
    @Description("A list of default columns for each input, in the case if they are generated or wildcard")
    public static final String OP_DEFAULT_COLS = "default.cols";
    @Description("A list of payload columns to use as a join key and pass to the output, sans the input prefix")
    public static final String OP_PAYLOAD_COLS = "payload.cols";
    @Description("Generated weighted sum per payload column")
    public static final String GEN_WEIGHTED_SUM = "_weighted_sum";
    @Description("Generated total per payload value column")
    public static final String GEN_TOTAL_VALUE = "_total_value";
    @Description("Generated total count per payload column")
    public static final String GEN_TOTAL_COUNT = "_total_count";
    @Description("All payload column names in the output will be prefixed with '_payload_'")
    public static final String GEN_PAYLOAD_PREFIX = "_payload_*";
    @Description("By default, no columns for wildcard inputs are specified")
    public static final String[] DEF_DEFAULT_COLS = null;

    public static final String VERB = "weightedSum";

    private static final Map<String, Integer> KNOWN_COLUMNS = new HashMap<String, Integer>() {{
        put(GEN_WEIGHTED_SUM, -1);
        put(GEN_TOTAL_VALUE, -2);
        put(GEN_TOTAL_COUNT, -3);
    }};

    private List<String> inputNames;
    private Map<String, Character> inputDelimiters;
    private String outputName;
    private char outputDelimiter;

    private int[] outputCols;

    private Map<String, Map<String, Integer>> payloadColumns;
    private Map<String, Integer> countColumns;
    private Map<String, Integer> valueColumns;

    @Override
    @Description("Take a number of CSV RDDs, and generate a weighted sum by count and value columns," +
            " while using a set of payload column values as a key to join inputs." +
            " Each combined payload value is not required to be unique per its input")
    public String verb() {
        return VERB;
    }

    @Override
    public TaskDescriptionLanguage.Operation description() {
        return new TaskDescriptionLanguage.Operation(verb(),
                new TaskDescriptionLanguage.DefBase[]{
                        new TaskDescriptionLanguage.Definition(OP_DEFAULT_COLS, String[].class, DEF_DEFAULT_COLS),
                        new TaskDescriptionLanguage.Definition(DS_COUNT_COL),
                        new TaskDescriptionLanguage.Definition(DS_VALUE_COL),
                        new TaskDescriptionLanguage.Definition(OP_PAYLOAD_COLS, String[].class),
                },

                new TaskDescriptionLanguage.OpStreams(
                        new TaskDescriptionLanguage.DataStream(
                                new TaskDescriptionLanguage.StreamType[]{TaskDescriptionLanguage.StreamType.CSV},
                                true
                        )
                ),

                new TaskDescriptionLanguage.OpStreams(
                        new TaskDescriptionLanguage.DataStream(
                                new TaskDescriptionLanguage.StreamType[]{TaskDescriptionLanguage.StreamType.CSV},
                                new ArrayList<String>(KNOWN_COLUMNS.keySet()) {{
                                    add(GEN_PAYLOAD_PREFIX);
                                }}
                        )
                )
        );
    }

    @Override
    public void configure(Properties properties, Properties variables) throws InvalidConfigValueException {
        super.configure(properties, variables);

        outputName = describedProps.outputs.get(0);
        outputDelimiter = dataStreamsProps.outputDelimiter(outputName);
        String[] outputColumns = dataStreamsProps.outputColumns.get(outputName);

        inputNames = describedProps.inputs;

        inputDelimiters = new HashMap<>();

        String[] defaultColumns = describedProps.defs.getTyped(OP_DEFAULT_COLS);
        Map<String, Integer> defaultMap = null;
        if (defaultColumns != null) {
            defaultMap = new HashMap<>();
            for (int columnNumber = 0; columnNumber < defaultColumns.length; columnNumber++) {
                String columnName = defaultColumns[columnNumber].trim();

                if (columnName.equals("_")) {
                    columnName = "_" + (columnNumber + 1) + "_";
                }

                defaultMap.put("." + columnName, columnNumber);
            }
        }

        String countColumnSuffix = "." + describedProps.defs.getTyped(DS_COUNT_COL);
        String valueColumnSuffix = "." + describedProps.defs.getTyped(DS_VALUE_COL);

        String[] payloadArr = describedProps.defs.getTyped(OP_PAYLOAD_COLS);
        Set<String> payloadColumnsSuffixes = Arrays.stream(payloadArr)
                .map(v -> "." + v)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<String, Integer> payloadCols = new LinkedHashMap<>();
        for (int i = 0; i < payloadArr.length; i++) {
            payloadCols.put(GEN_PAYLOAD_PREFIX.replace("*", payloadArr[i]), i);
        }

        countColumns = new HashMap<>();
        valueColumns = new HashMap<>();
        payloadColumns = new HashMap<>();

        for (final String inputName : inputNames) {
            inputDelimiters.put(inputName, dataStreamsProps.inputDelimiter(inputName));

            Map<String, Integer> thisInputColumns = dataStreamsProps.inputColumns.get(inputName);
            if (thisInputColumns.size() == 0) {
                if (defaultMap == null) {
                    throw new InvalidConfigValueException("Input " + inputName + " does not have column specification nor default columns are specified in the operation '" + name + "'");
                }

                thisInputColumns = defaultMap.entrySet().stream()
                        .collect(Collectors.toMap(c -> inputName + c.getKey(),
                                Map.Entry::getValue));
            }

            for (Map.Entry<String, Integer> e : thisInputColumns.entrySet()) {
                if (e.getKey().endsWith(countColumnSuffix)) {
                    countColumns.put(inputName, e.getValue());
                }
                if (e.getKey().endsWith(valueColumnSuffix)) {
                    valueColumns.put(inputName, e.getValue());
                }
            }

            payloadColumns.put(inputName, new LinkedHashMap<>());

            for (String payloadColumnSuffix : payloadColumnsSuffixes) {
                for (Map.Entry<String, Integer> e : thisInputColumns.entrySet()) {
                    if (e.getKey().endsWith(payloadColumnSuffix)) {
                        payloadColumns.get(inputName).put(e.getKey(), e.getValue());
                    }
                }
            }
        }

        if (countColumns.size() != inputNames.size()) {
            throw new InvalidConfigValueException("Not all inputs have set a count column with suffix '" + countColumnSuffix + "' in the operation '" + name + "'");
        }
        if (valueColumns.size() != inputNames.size()) {
            throw new InvalidConfigValueException("Not all inputs have set a value column with suffix '" + valueColumnSuffix + "' in the operation '" + name + "'");
        }
        for (final String inputName : inputNames) {
            if (payloadColumns.get(inputName).size() != payloadCols.size()) {
                throw new InvalidConfigValueException("Input '" + inputName + "' doesn't have all payload columns set in the operation '" + name + "'");
            }
        }

        outputCols = new int[outputColumns.length];
        int i = 0;
        for (String outputColumn : outputColumns) {
            if (KNOWN_COLUMNS.containsKey(outputColumn)) {
                outputCols[i] = KNOWN_COLUMNS.get(outputColumn);
            } else {
                outputCols[i] = payloadCols.get(outputColumn);
            }

            i++;
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, JavaRDDLike> getResult(Map<String, JavaRDDLike> input) {
        final char _outputDelimiter = outputDelimiter;
        final int[] _outputColumns = outputCols;

        List<JavaPairRDD<Payload, Tuple2<Long, Double>>> inputs = new ArrayList<>();
        for (String inputName : inputNames) {
            final char inputDelimiter = inputDelimiters.get(inputName);
            final int countColumn = countColumns.get(inputName);
            final int valueColumn = valueColumns.get(inputName);
            final int[] payloads = payloadColumns.get(inputName).values().stream()
                    .mapToInt(e -> e)
                    .toArray();

            for (String inp : getMatchingInputs(input.keySet(), inputName)) {
                JavaPairRDD<Payload, Tuple2<Long, Double>> namedInput = ((JavaRDD<Object>) input.get(inp))
                        .mapPartitionsToPair(it -> {
                            List<Tuple2<Payload, Tuple2<Long, Double>>> result = new ArrayList<>();

                            CSVParser parser = new CSVParserBuilder()
                                    .withSeparator(inputDelimiter).build();

                            while (it.hasNext()) {
                                String l = String.valueOf(it.next());
                                String[] row = parser.parseLine(l);

                                Long count = new Long(row[countColumn]);
                                Double value = new Double(row[valueColumn]);

                                Text[] payload = new Text[payloads.length];
                                int j = 0;
                                for (int p : payloads) {
                                    payload[j++] = new Text(row[p]);
                                }

                                result.add(new Tuple2<>(new Payload(payload), new Tuple2<>(count, value)));
                            }

                            return result.iterator();
                        });

                inputs.add(namedInput);
            }
        }

        JavaPairRDD<Payload, Tuple2<Long, Double>> inputsUnion = ctx
                .<Payload, Tuple2<Long, Double>>union(inputs.toArray(new JavaPairRDD[0]));

        JavaPairRDD<Payload, Tuple2<Long, Double>> weightedSums = inputsUnion
                .mapToPair(t -> new Tuple2<>(t._1, new Tuple2<>(t._2._1, t._2._1 * t._2._2)))
                .reduceByKey((t1, t2) -> new Tuple2<>(t1._1 + t2._1, t1._2 + t2._2));

        JavaRDD<Text> output = weightedSums
                .mapPartitions(it -> {
                    List<Text> result = new ArrayList<>();

                    while (it.hasNext()) {
                        Tuple2<Payload, Tuple2<Long, Double>> t = it.next();

                        Text[] payload = t._1.payload;

                        Long totalCount = t._2._1;
                        Double totalValue = t._2._2;
                        Double weightedSum = totalValue / totalCount;

                        StringWriter buffer = new StringWriter();
                        String[] acc = new String[_outputColumns.length];

                        for (int j = 0; j < _outputColumns.length; j++) {
                            int outputColumn = _outputColumns[j];
                            switch (outputColumn) {
                                case -3: {
                                    acc[j] = totalCount.toString();
                                    break;
                                }
                                case -2: {
                                    acc[j] = totalValue.toString();
                                    break;
                                }
                                case -1: {
                                    acc[j] = weightedSum.toString();
                                    break;
                                }
                                default: {
                                    acc[j] = payload[outputColumn].toString();
                                }
                            }
                        }

                        CSVWriter writer = new CSVWriter(buffer, _outputDelimiter, CSVWriter.DEFAULT_QUOTE_CHARACTER,
                                CSVWriter.DEFAULT_ESCAPE_CHARACTER, "");
                        writer.writeNext(acc, false);
                        writer.close();

                        result.add(new Text(buffer.toString()));
                    }

                    return result.iterator();
                });

        return Collections.singletonMap(outputName, output);
    }

    public static class Payload implements Serializable {
        public Text[] payload;

        public Payload(Text[] payload) {
            this.payload = payload;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Payload)) {
                return false;
            }
            return Arrays.equals(payload, ((Payload) other).payload);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(payload);
        }
    }
}