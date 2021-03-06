/**
 * Copyright (C) 2020 Locomizer team and Contributors
 * This project uses New BSD license with do no evil clause. For full text, check the LICENSE file in the root directory.
 */
package ash.nazg.populations.operations;

import ash.nazg.config.InvalidConfigValueException;
import ash.nazg.config.tdl.Description;
import ash.nazg.config.OperationConfig;
import ash.nazg.config.tdl.TaskDescriptionLanguage;
import ash.nazg.populations.functions.CountUniquesFunction;
import ash.nazg.spark.Operation;
import com.opencsv.CSVParser;
import com.opencsv.CSVParserBuilder;
import com.opencsv.CSVWriter;
import org.apache.hadoop.io.Text;
import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaRDDLike;
import scala.Tuple2;

import java.io.StringWriter;
import java.util.*;

import static ash.nazg.populations.config.ConfigurationParameters.*;

@SuppressWarnings("unused")
public class ReachOperation extends Operation {
    private static final String VERB = "reach";

    private int signalsUseridColumn;
    private int targetUseridColumn;
    private int targetGidColumn;
    private String inputSignalsName;
    private char inputSignalsDelimiter;
    private String inputTargetName;
    private char inputTargetDelimiter;
    private String outputName;
    private char outputDelimiter;

    @Override
    @Description("Statistical indicator for some audience reach")
    public String verb() {
        return VERB;
    }

    @Override
    public TaskDescriptionLanguage.Operation description() {
        return new TaskDescriptionLanguage.Operation(verb(),
                new TaskDescriptionLanguage.DefBase[]{
                        new TaskDescriptionLanguage.Definition(DS_SIGNALS_USERID_COLUMN),
                        new TaskDescriptionLanguage.Definition(DS_TARGET_USERID_COLUMN),
                        new TaskDescriptionLanguage.Definition(DS_TARGET_GID_COLUMN),
                },

                new TaskDescriptionLanguage.OpStreams(new TaskDescriptionLanguage.NamedStream[]{
                        new TaskDescriptionLanguage.NamedStream(RDD_INPUT_SIGNALS,
                                new TaskDescriptionLanguage.StreamType[]{TaskDescriptionLanguage.StreamType.CSV},
                                true
                        ),
                        new TaskDescriptionLanguage.NamedStream(RDD_INPUT_TARGET,
                                new TaskDescriptionLanguage.StreamType[]{TaskDescriptionLanguage.StreamType.CSV},
                                true
                        )
                }),

                new TaskDescriptionLanguage.OpStreams(
                        new TaskDescriptionLanguage.DataStream(
                                new TaskDescriptionLanguage.StreamType[]{TaskDescriptionLanguage.StreamType.Fixed},
                                false
                        )
                )
        );
    }

    @Override
    public void configure(Properties properties, Properties variables) throws InvalidConfigValueException {
        super.configure(properties, variables);

        inputSignalsName = describedProps.namedInputs.get(RDD_INPUT_SIGNALS);
        inputSignalsDelimiter = dataStreamsProps.inputDelimiter(inputSignalsName);

        Map<String, Integer> inputColumns = dataStreamsProps.inputColumns.get(inputSignalsName);
        String prop;

        prop = describedProps.defs.getTyped(DS_SIGNALS_USERID_COLUMN);
        signalsUseridColumn = inputColumns.get(prop);

        inputTargetName = describedProps.namedInputs.get(RDD_INPUT_TARGET);
        inputTargetDelimiter = dataStreamsProps.inputDelimiter(inputTargetName);

        inputColumns = dataStreamsProps.inputColumns.get(inputTargetName);

        prop = describedProps.defs.getTyped(DS_TARGET_USERID_COLUMN);
        targetUseridColumn = inputColumns.get(prop);

        prop = describedProps.defs.getTyped(DS_TARGET_GID_COLUMN);
        targetGidColumn = inputColumns.get(prop);

        outputName = describedProps.outputs.get(0);
        outputDelimiter = dataStreamsProps.outputDelimiter(outputName);
    }

    @Override
    public Map<String, JavaRDDLike> getResult(Map<String, JavaRDDLike> input) {
        char _inputSignalsDelimiter = inputSignalsDelimiter;
        int _signalsUseridColumn = signalsUseridColumn;

        final long N = ((JavaRDD<Object>) input.get(inputSignalsName))
                .mapPartitionsToPair(it -> {
                    CSVParser parser = new CSVParserBuilder()
                            .withSeparator(_inputSignalsDelimiter).build();

                    List<Tuple2<Text, Void>> ret = new ArrayList<>();
                    while (it.hasNext()) {
                        Object o = it.next();
                        String l = (o instanceof String) ? (String) o : String.valueOf(o);

                        String[] row = parser.parseLine(l);

                        Text userid = new Text(row[_signalsUseridColumn]);

                        ret.add(new Tuple2<>(userid, null));
                    }

                    return ret.iterator();
                })
                .distinct()
                .count();

        JavaPairRDD<Text, Integer> userPerGid = new CountUniquesFunction(inputTargetDelimiter, targetGidColumn, targetUseridColumn)
                .call((JavaRDD<Object>) input.get(inputTargetName));

        final char _outputDelimiter = outputDelimiter;

        JavaRDD<Text> output = userPerGid.mapPartitions(it -> {
            List<Text> ret = new ArrayList<>();

            while (it.hasNext()) {
                Tuple2<Text, Integer> t = it.next();

                String[] acc = new String[]{t._1.toString(), Double.toString(t._2.doubleValue() / N)};

                StringWriter buffer = new StringWriter();
                CSVWriter writer = new CSVWriter(buffer, _outputDelimiter, CSVWriter.DEFAULT_QUOTE_CHARACTER,
                        CSVWriter.DEFAULT_ESCAPE_CHARACTER, "");
                writer.writeNext(acc, false);
                writer.close();

                ret.add(new Text(buffer.toString()));
            }

            return ret.iterator();
        });

        return Collections.singletonMap(outputName, output);
    }
}
