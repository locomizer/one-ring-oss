/**
 * Copyright (C) 2020 Locomizer team and Contributors
 * This project uses New BSD license with do no evil clause. For full text, check the LICENSE file in the root directory.
 */
package ash.nazg.config.tdl;

import ash.nazg.commons.operations.MapToPairOperation;
import ash.nazg.config.Packages;
import ash.nazg.spark.OpInfo;
import ash.nazg.spark.Operations;
import ash.nazg.spatial.config.ConfigurationParameters;
import ash.nazg.spatial.operations.*;
import ash.nazg.storage.Adapters;
import ash.nazg.storage.HadoopAdapter;
import ash.nazg.storage.InputAdapter;
import ash.nazg.storage.StorageAdapter;
import org.apache.velocity.Template;
import org.apache.velocity.VelocityContext;
import org.apache.velocity.app.Velocity;

import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static ash.nazg.config.OperationConfig.*;
import static ash.nazg.config.WrapperConfig.*;
import static ash.nazg.config.tdl.PropertiesConverter.DEFAULT_DS;
import static java.nio.charset.StandardCharsets.UTF_8;

public class DocumentationGenerator {
    public static TaskDefinitionLanguage.Task createExampleTask(OpInfo opInfo, TaskDocumentationLanguage.Operation opDoc,  String prefix) {
        List<TaskDefinitionLanguage.Operation> ops = new ArrayList<>();
        List<TaskDefinitionLanguage.DataStream> streams = new ArrayList<>();
        List<String> sink = new ArrayList<>();
        List<String> tees = new ArrayList<>();

        TaskDefinitionLanguage.Operation opDef = new TaskDefinitionLanguage.Operation();
        String verb = opInfo.verb();
        TaskDescriptionLanguage.Operation descr = opInfo.description();

        opDef.verb = verb;
        opDef.name = verb;

        Map<String, List<String>> namedColumns = new HashMap<>();
        namedColumns.put(null, new ArrayList<>());

        if (descr.definitions != null) {
            List<TaskDefinitionLanguage.Definition> defs = new ArrayList<>();
            List<TaskDefinitionLanguage.DynamicDef> dynDefs = new ArrayList<>();

            Map<String, String> manDescr = parameterListToMap(opDoc.getMandatoryParameters());
            Map<String, String> optDescr = parameterListToMap(opDoc.getOptionalParameters());
            Map<String, String> dynDescr = parameterListToMap(opDoc.getDynamicParameters());

            for (TaskDescriptionLanguage.DefBase db : descr.definitions) {
                if (db instanceof TaskDescriptionLanguage.Definition) {
                    TaskDescriptionLanguage.Definition def = (TaskDescriptionLanguage.Definition) db;

                    TaskDefinitionLanguage.Definition d = new TaskDefinitionLanguage.Definition();
                    d.name = def.name;

                    if (def.optional) {
                        d.value = "<* " + def.defaults + ": " + optDescr.get(def.name) + " *>";
                    } else {
                        if (def.clazz.isEnum()) {
                            d.value = Arrays.stream(def.enumValues).collect(Collectors.joining(", ", "<* One of: ", " *>"));
                        } else {
                            d.value = "<* " + def.clazz.getSimpleName() + ": " + manDescr.get(def.name) + " *>";
                        }
                    }

                    if (def.name.endsWith(COLUMN_SUFFIX)) {
                        String[] col = def.name.split("\\.", 3);

                        if (col.length == 3) {
                            namedColumns.computeIfAbsent(col[0], x -> new ArrayList<>());
                            namedColumns.get(col[0]).add(col[1]);

                            d.value = verb + "_" + col[0] + "." + col[1];
                        } else if (col.length == 2) {
                            namedColumns.get(null).add(col[0]);

                            d.value = verb + "_0." + col[0];
                        }
                    }

                    if (def.optional) {
                        d.useDefaults = Objects.equals(d.value, def.defaults);
                    }

                    defs.add(d);
                } else {
                    TaskDescriptionLanguage.DynamicDef dyn = (TaskDescriptionLanguage.DynamicDef) db;

                    TaskDefinitionLanguage.DynamicDef d = new TaskDefinitionLanguage.DynamicDef();

                    d.name = dyn.prefix + "*";
                    if (dyn.clazz.isEnum()) {
                        d.value = Arrays.stream(dyn.enumValues).collect(Collectors.joining(" ", "<* One of: ", " *>"));
                    } else {
                        d.value = "<* " + dyn.clazz.getSimpleName() + ": " + dynDescr.get(dyn.prefix) + " *>";
                    }

                    dynDefs.add(d);
                }
            }

            opDef.definitions = defs.toArray(new TaskDefinitionLanguage.Definition[0]);
            opDef.dynamicDefs = dynDefs.toArray(new TaskDefinitionLanguage.DynamicDef[0]);
        }

        AtomicInteger dsNum = new AtomicInteger();

        opDef.inputs = new TaskDefinitionLanguage.OpStreams();

        if (descr.inputs.positional != null) {
            TaskDescriptionLanguage.StreamType st = descr.inputs.positional.types[0];

            List<String> posInputs = new ArrayList<>();
            int cnt = (descr.inputs.positionalMinCount != null) ? descr.inputs.positionalMinCount : 1;
            for (int i = 0; i < cnt; i++) {
                String name = verb + "_" + i;

                List<String> columns = null;
                if (descr.inputs.positional.columnBased) {
                    columns = new ArrayList<>();

                    List<String> named = namedColumns.get(null);
                    if ((named != null) && !named.isEmpty()) {
                        columns.addAll(named);
                    } else {
                        columns.add("<* A list of columns *>");
                    }
                }

                createSourceOps(name, dsNum, st, columns, ops, streams, sink);
                posInputs.add(name);
            }

            opDef.inputs.positionalNames = posInputs.toArray(new String[0]);
        } else if (descr.inputs.named != null) {
            List<TaskDefinitionLanguage.NamedStream> nstreams = new ArrayList<>();
            Arrays.stream(descr.inputs.named)
                    .forEach(nsDesc -> {
                        TaskDescriptionLanguage.StreamType st = nsDesc.types[0];

                        List<String> columns = null;
                        if (nsDesc.columnBased) {
                            columns = new ArrayList<>();

                            List<String> named = namedColumns.get(nsDesc.name);
                            if ((named != null) && !named.isEmpty()) {
                                columns.addAll(named);
                            } else {
                                columns.add("<* A list of columns *>");
                            }
                        }

                        createSourceOps(verb + "_" + nsDesc.name, dsNum, st, columns, ops, streams, sink);

                        TaskDefinitionLanguage.NamedStream ns = new TaskDefinitionLanguage.NamedStream();
                        ns.name = nsDesc.name;
                        ns.value = verb + "_" + nsDesc.name;

                        nstreams.add(ns);
                    });
            opDef.inputs.named = nstreams.toArray(new TaskDefinitionLanguage.NamedStream[0]);
        }

        ops.add(opDef);

        opDef.outputs = new TaskDefinitionLanguage.OpStreams();

        if (descr.outputs.positional != null) {
            TaskDescriptionLanguage.StreamType st = descr.outputs.positional.types[0];

            List<String> columns = null;
            if (descr.outputs.positional.columnBased) {
                columns = new ArrayList<>();

                if ((descr.outputs.positional.generatedColumns != null) && (descr.outputs.positional.generatedColumns.length > 0)) {
                    columns.addAll(Arrays.asList(descr.outputs.positional.generatedColumns));
                } else {
                    columns.add("<* A list of columns from input(s) goes here *>");
                }
            }

            createOutput(verb, dsNum, st, columns, ops, streams, tees);

            opDef.outputs.positionalNames = new String[]{verb};
        } else if (descr.outputs.named != null) {
            List<TaskDefinitionLanguage.NamedStream> nstreams = new ArrayList<>();
            Arrays.stream(descr.outputs.named)
                    .forEach(nsDesc -> {
                        TaskDescriptionLanguage.StreamType st = nsDesc.types[0];

                        List<String> columns = null;
                        if (nsDesc.columnBased) {
                            columns = new ArrayList<>();

                            if ((nsDesc.generatedColumns != null) && (nsDesc.generatedColumns.length > 0)) {
                                columns.addAll(Arrays.asList(nsDesc.generatedColumns));
                            } else {
                                columns.add("<* A list of columns from input(s) goes here *>");
                            }
                        }

                        createOutput(nsDesc.name, dsNum, st, columns, ops, streams, tees);

                        TaskDefinitionLanguage.NamedStream ns = new TaskDefinitionLanguage.NamedStream();
                        ns.name = nsDesc.name;
                        ns.value = nsDesc.name;

                        nstreams.add(ns);
                    });
            opDef.outputs.named = nstreams.toArray(new TaskDefinitionLanguage.NamedStream[0]);
        }

        TaskDefinitionLanguage.DataStream defDs = new TaskDefinitionLanguage.DataStream();
        defDs.name = DEFAULT_DS;
        defDs.output = new TaskDefinitionLanguage.StreamDesc();
        defDs.output.path = "proto://full/path/to/output/directory";
        streams.add(defDs);

        TaskDefinitionLanguage.Task task = new TaskDefinitionLanguage.Task();
        task.prefix = prefix;
        task.operations = ops.toArray(new TaskDefinitionLanguage.Operation[0]);
        task.dataStreams = streams.toArray(new TaskDefinitionLanguage.DataStream[0]);
        task.sink = sink.toArray(new String[0]);
        task.tees = tees.toArray(new String[0]);

        return task;
    }

    private static Map<String, String> parameterListToMap(List<TaskDocumentationLanguage.Parameter> pl) {
        return pl.stream().collect(Collectors.toMap(l -> l.name, l -> l.descr));
    }

    private static void createSourceOps(String name, AtomicInteger dsNum, TaskDescriptionLanguage.StreamType st, List<String> columns, List<TaskDefinitionLanguage.Operation> ops, List<TaskDefinitionLanguage.DataStream> streams, List<String> sink) {
        switch (st) {
            case Plain:
            case CSV: {
                TaskDefinitionLanguage.DataStream input = new TaskDefinitionLanguage.DataStream();
                input.name = name;
                if (columns != null) {
                    input.input = new TaskDefinitionLanguage.StreamDesc();
                    input.input.columns = columns.toArray(new String[0]);
                    input.input.delimiter = ",";
                    input.input.path = "proto://full/path/to/source_" + dsNum + "/*.csv";
                    input.input.partCount = "100500";
                }

                streams.add(input);
                sink.add(name);

                break;
            }
            case Point: {
                TaskDefinitionLanguage.Operation src = new TaskDefinitionLanguage.Operation();
                src.verb = PointCSVSourceOperation.VERB;
                src.name = "source_" + dsNum.incrementAndGet();
                src.inputs = new TaskDefinitionLanguage.OpStreams();
                src.inputs.positionalNames = new String[]{"source_" + dsNum};
                src.outputs = new TaskDefinitionLanguage.OpStreams();
                src.outputs.positionalNames = new String[]{name};
                src.definitions = new TaskDefinitionLanguage.Definition[3];
                src.definitions[0] = new TaskDefinitionLanguage.Definition();
                src.definitions[0].name = ConfigurationParameters.DS_CSV_RADIUS_COLUMN;
                src.definitions[0].value = "source_" + dsNum + ".radius";
                src.definitions[1] = new TaskDefinitionLanguage.Definition();
                src.definitions[1].name = ConfigurationParameters.DS_CSV_LAT_COLUMN;
                src.definitions[1].value = "source_" + dsNum + ".lat";
                src.definitions[2] = new TaskDefinitionLanguage.Definition();
                src.definitions[2].name = ConfigurationParameters.DS_CSV_LON_COLUMN;
                src.definitions[2].value = "source_" + dsNum + ".lon";

                ops.add(src);

                TaskDefinitionLanguage.DataStream input = new TaskDefinitionLanguage.DataStream();
                input.name = "source_" + dsNum;
                input.input = new TaskDefinitionLanguage.StreamDesc();
                input.input.columns = new String[]{"lat", "lon", "radius"};
                input.input.path = "proto://full/path/to/source_" + dsNum + "/*.csv";
                input.input.partCount = "100500";

                streams.add(input);
                sink.add("source_" + dsNum);

                TaskDefinitionLanguage.DataStream output = new TaskDefinitionLanguage.DataStream();
                output.name = name;
                if (columns != null) {
                    output.input = new TaskDefinitionLanguage.StreamDesc();
                    output.input.columns = columns.toArray(new String[0]);
                    output.input.delimiter = ",";
                }

                streams.add(output);

                break;
            }
            case Track: {
                TaskDefinitionLanguage.Operation src = new TaskDefinitionLanguage.Operation();
                src.verb = TrackGPXSourceOperation.VERB;
                src.name = "source_" + dsNum.incrementAndGet();
                src.inputs = new TaskDefinitionLanguage.OpStreams();
                src.inputs.positionalNames = new String[]{"source_" + dsNum};
                src.outputs = new TaskDefinitionLanguage.OpStreams();
                src.outputs.positionalNames = new String[]{name};

                ops.add(src);

                TaskDefinitionLanguage.DataStream input = new TaskDefinitionLanguage.DataStream();
                input.name = "source_" + dsNum;
                input.input = new TaskDefinitionLanguage.StreamDesc();
                input.input.path = "proto://full/path/to/source_" + dsNum + "/*.gpx";
                input.input.partCount = "100500";

                streams.add(input);
                sink.add("source_" + dsNum);

                TaskDefinitionLanguage.DataStream output = new TaskDefinitionLanguage.DataStream();
                output.name = name;
                if (columns != null) {
                    output.input = new TaskDefinitionLanguage.StreamDesc();
                    output.input.columns = columns.toArray(new String[0]);
                    output.input.delimiter = ",";
                }

                streams.add(output);

                break;
            }
            case Polygon: {
                TaskDefinitionLanguage.Operation src = new TaskDefinitionLanguage.Operation();
                src.verb = PolygonJSONSourceOperation.VERB;
                src.name = "source_" + dsNum.incrementAndGet();
                src.inputs = new TaskDefinitionLanguage.OpStreams();
                src.inputs.positionalNames = new String[]{"source_" + dsNum};
                src.outputs = new TaskDefinitionLanguage.OpStreams();
                src.outputs.positionalNames = new String[]{name};

                ops.add(src);

                TaskDefinitionLanguage.DataStream input = new TaskDefinitionLanguage.DataStream();
                input.name = "source_" + dsNum;
                input.input = new TaskDefinitionLanguage.StreamDesc();
                input.input.path = "proto://full/path/to/source_" + dsNum + "/*.json";
                input.input.partCount = "100500";

                streams.add(input);
                sink.add("source_" + dsNum);

                TaskDefinitionLanguage.DataStream output = new TaskDefinitionLanguage.DataStream();
                output.name = name;
                if (columns != null) {
                    output.input = new TaskDefinitionLanguage.StreamDesc();
                    output.input.columns = columns.toArray(new String[0]);
                    output.input.delimiter = ",";
                }

                streams.add(output);

                break;
            }
            case KeyValue: {
                TaskDefinitionLanguage.DataStream inter = new TaskDefinitionLanguage.DataStream();
                inter.name = name + "_0";
                inter.input = new TaskDefinitionLanguage.StreamDesc();
                inter.input.columns = new String[]{"key", "_"};
                inter.input.delimiter = ",";
                inter.input.path = "proto://full/path/to/source_" + dsNum + "/*.csv";
                inter.input.partCount = "100500";

                streams.add(inter);
                sink.add(name + "_0");

                TaskDefinitionLanguage.Operation toPair = new TaskDefinitionLanguage.Operation();
                toPair.verb = MapToPairOperation.VERB;
                toPair.name = "source_" + dsNum.incrementAndGet();
                toPair.inputs = new TaskDefinitionLanguage.OpStreams();
                toPair.inputs.positionalNames = new String[]{name + "_0"};
                toPair.outputs = new TaskDefinitionLanguage.OpStreams();
                toPair.outputs.positionalNames = new String[]{name};
                toPair.definitions = new TaskDefinitionLanguage.Definition[1];
                toPair.definitions[0] = new TaskDefinitionLanguage.Definition();
                toPair.definitions[0].name = MapToPairOperation.OP_KEY_COLUMNS;
                toPair.definitions[0].value = name + "_0.key";

                ops.add(toPair);

                TaskDefinitionLanguage.DataStream output = new TaskDefinitionLanguage.DataStream();
                output.name = name;
                if (columns != null) {
                    output.input = new TaskDefinitionLanguage.StreamDesc();
                    output.input.columns = columns.toArray(new String[0]);
                    output.input.delimiter = ",";
                }

                streams.add(output);

                break;
            }
        }
    }

    private static void createOutput(String name, AtomicInteger dsNum, TaskDescriptionLanguage.StreamType st, List<String> columns, List<TaskDefinitionLanguage.Operation> ops, List<TaskDefinitionLanguage.DataStream> streams, List<String> tees) {
        switch (st) {
            case Point: {
                TaskDefinitionLanguage.Operation out = new TaskDefinitionLanguage.Operation();
                out.verb = PointCSVOutputOperation.VERB;
                out.name = "output_" + dsNum.incrementAndGet();
                out.inputs = new TaskDefinitionLanguage.OpStreams();
                out.inputs.positionalNames = new String[]{name};
                out.outputs = new TaskDefinitionLanguage.OpStreams();
                out.outputs.positionalNames = new String[]{name + "_output"};

                ops.add(out);

                TaskDefinitionLanguage.DataStream output = new TaskDefinitionLanguage.DataStream();
                output.name = name + "_output";
                if (columns != null) {
                    output.output = new TaskDefinitionLanguage.StreamDesc();
                    output.output.columns = columns.toArray(new String[0]);
                    output.output.delimiter = ",";
                }

                streams.add(output);
                tees.add(name + "_output");
                break;
            }
            case Track: {
                TaskDefinitionLanguage.Operation out = new TaskDefinitionLanguage.Operation();
                out.verb = TrackGPXOutputOperation.VERB;
                out.name = "output_" + dsNum.incrementAndGet();
                out.inputs = new TaskDefinitionLanguage.OpStreams();
                out.inputs.positionalNames = new String[]{name};
                out.outputs = new TaskDefinitionLanguage.OpStreams();
                out.outputs.positionalNames = new String[]{name + "_output"};

                ops.add(out);

                TaskDefinitionLanguage.DataStream output = new TaskDefinitionLanguage.DataStream();
                output.name = name + "_output";
                output.output = new TaskDefinitionLanguage.StreamDesc();
                output.output.columns = new String[]{name + ".lat", name + ".lon", name + ".radius",};
                output.output.delimiter = ",";

                streams.add(output);
                tees.add(name + "_output");
                break;
            }
            case Polygon: {
                TaskDefinitionLanguage.Operation out = new TaskDefinitionLanguage.Operation();
                out.verb = PolygonJSONOutputOperation.VERB;
                out.name = "output_" + dsNum.incrementAndGet();
                out.inputs = new TaskDefinitionLanguage.OpStreams();
                out.inputs.positionalNames = new String[]{name};
                out.outputs = new TaskDefinitionLanguage.OpStreams();
                out.outputs.positionalNames = new String[]{name + "_output"};

                ops.add(out);

                TaskDefinitionLanguage.DataStream output = new TaskDefinitionLanguage.DataStream();
                output.name = name + "_output";
                if (columns != null) {
                    output.output = new TaskDefinitionLanguage.StreamDesc();
                    output.output.columns = columns.toArray(new String[0]);
                    output.output.delimiter = ",";
                }

                streams.add(output);
                tees.add(name + "_output");
                break;
            }
            default: {
                TaskDefinitionLanguage.DataStream output = new TaskDefinitionLanguage.DataStream();
                output.name = name;
                if (columns != null) {
                    output.output = new TaskDefinitionLanguage.StreamDesc();
                    output.output.columns = columns.toArray(new String[0]);
                    output.output.delimiter = ",";
                }

                streams.add(output);
                tees.add(name);
                break;
            }
        }
    }

    public static void packageDoc(String pkgName, Writer writer) throws Exception {
        String descr = Packages.getRegisteredPackages().get(pkgName);

        TaskDocumentationLanguage.Package pkg = new TaskDocumentationLanguage.Package(pkgName, descr);

        List<TaskDocumentationLanguage.Pair> ops = pkg.ops;
        List<TaskDocumentationLanguage.Pair> adapters = pkg.adapters;

        for (Map.Entry<String, OpInfo> oi : Operations.getAvailableOperations(pkgName).entrySet()) {
            Method verb = oi.getValue().getClass().getDeclaredMethod("verb");
            Description d = verb.getDeclaredAnnotation(Description.class);

            ops.add(new TaskDocumentationLanguage.Pair(oi.getKey(), d.value()));
        }

        for (Map.Entry<String, StorageAdapter> ai : Adapters.getAvailableStorageAdapters(pkgName).entrySet()) {
            Method proto = ai.getValue().getClass().getMethod("proto");
            Description d = proto.getDeclaredAnnotation(Description.class);

            adapters.add(new TaskDocumentationLanguage.Pair(ai.getKey(), d.value()));
        }

        VelocityContext ic = new VelocityContext();
        ic.put("pkg", pkg);

        Template index = Velocity.getTemplate("package.vm", UTF_8.name());
        index.merge(ic, writer);
    }

    public static void indexDoc(Map<String, String> packages, FileWriter writer) {
        List<TaskDocumentationLanguage.Pair> pkgs = new ArrayList<>();

        for (Map.Entry<String, String> pkg : packages.entrySet()) {
            pkgs.add(new TaskDocumentationLanguage.Pair(pkg.getKey(), pkg.getValue()));
        }

        VelocityContext ic = new VelocityContext();
        ic.put("pkgs", pkgs);

        Template index = Velocity.getTemplate("index.vm", UTF_8.name());
        index.merge(ic, writer);
    }

    public static TaskDocumentationLanguage.Operation operationDoc(OpInfo opInfo) throws Exception {
        TaskDocumentationLanguage.Operation opDoc = new TaskDocumentationLanguage.Operation();

        opDoc.verb = opInfo.verb();

        Class<? extends OpInfo> operationClass = opInfo.getClass();

        Method verb = operationClass.getDeclaredMethod("verb");
        Description d = verb.getDeclaredAnnotation(Description.class);
        opDoc.descr = d.value();

        opDoc.pkg = operationClass.getPackage().getName();

        Descriptions ds = Descriptions.inspectOperation(operationClass);

        TaskDescriptionLanguage.Operation descr = opInfo.description();

        List<TaskDocumentationLanguage.Parameter> mandatoryParameters = opDoc.mandatoryParameters;
        List<TaskDocumentationLanguage.Parameter> optionalParameters = opDoc.optionalParameters;
        List<TaskDocumentationLanguage.Parameter> dynamicParameters = opDoc.dynamicParameters;
        if (descr.definitions != null) {
            for (TaskDescriptionLanguage.DefBase db : descr.definitions) {
                TaskDocumentationLanguage.Parameter param;

                Field f;
                if (db instanceof TaskDescriptionLanguage.Definition) {
                    TaskDescriptionLanguage.Definition def = (TaskDescriptionLanguage.Definition) db;

                    f = ds.fields.get(ds.definitions.get(def.name));
                    d = f.getDeclaredAnnotation(Description.class);

                    param = new TaskDocumentationLanguage.Parameter(def.name, d.value());

                    param.type = def.clazz.getSimpleName();
                    if (def.clazz.isEnum()) {
                        param.values = getEnumDocs(def.clazz);
                    }

                    if (def.optional) {
                        String strippedName = ds.definitions.get(def.name)
                                .replaceFirst("^DS_", "")
                                .replaceFirst("^OP_", "");

                        f = ds.fields.get("DEF_" + strippedName);
                        d = f.getDeclaredAnnotation(Description.class);

                        param.defaults = new TaskDocumentationLanguage.Pair(def.defaults, d.value());

                        optionalParameters.add(param);
                    } else {
                        mandatoryParameters.add(param);
                    }
                } else {
                    TaskDescriptionLanguage.DynamicDef dyn = (TaskDescriptionLanguage.DynamicDef) db;

                    f = ds.fields.get(ds.definitions.get(dyn.prefix));
                    d = f.getDeclaredAnnotation(Description.class);

                    param = new TaskDocumentationLanguage.Parameter(dyn.prefix, d.value());

                    param.type = dyn.clazz.getSimpleName();
                    if (dyn.clazz.isEnum()) {
                        param.values = getEnumDocs(dyn.clazz);
                    }

                    dynamicParameters.add(param);
                }
            }
        }

        List<TaskDocumentationLanguage.Input> namedInputs = opDoc.namedInputs;
        if (descr.inputs.positional != null) {
            TaskDocumentationLanguage.Input input = new TaskDocumentationLanguage.Input(null, null);

            input.type = Arrays.stream(descr.inputs.positional.types)
                    .map(TaskDescriptionLanguage.StreamType::name)
                    .collect(Collectors.toList());

            opDoc.positionalInputs = input;
            opDoc.positionalMin = descr.inputs.positionalMinCount;
        } else if (descr.inputs.named != null) {
            Arrays.stream(descr.inputs.named)
                    .forEach(ns -> {
                        Field f = ds.fields.get(ds.inputs.get(ns.name));
                        Description de = f.getDeclaredAnnotation(Description.class);

                        TaskDocumentationLanguage.Input input = new TaskDocumentationLanguage.Input(ns.name, de.value());

                        input.type = Arrays.stream(ns.types)
                                .map(TaskDescriptionLanguage.StreamType::name)
                                .collect(Collectors.toList());

                        namedInputs.add(input);
                    });
        }

        List<TaskDocumentationLanguage.Output> namedOutputs = opDoc.namedOutputs;
        if (descr.outputs.positional != null) {
            TaskDocumentationLanguage.Output output = new TaskDocumentationLanguage.Output(null, null);

            output.type = Arrays.stream(descr.outputs.positional.types)
                    .map(TaskDescriptionLanguage.StreamType::name)
                    .collect(Collectors.toList());

            output.generated = new ArrayList<>();
            if (descr.outputs.positional.generatedColumns != null) {
                Arrays.stream(descr.outputs.positional.generatedColumns)
                        .forEach(gen -> {
                            Field fG = ds.fields.get(ds.generated.get(gen));
                            Description deG = fG.getDeclaredAnnotation(Description.class);

                            output.generated.add(new TaskDocumentationLanguage.Pair(gen, deG.value()));
                        });
            }

            opDoc.positionalOutputs = output;
        } else if (descr.outputs.named != null) {
            Arrays.stream(descr.outputs.named)
                    .forEach(ns -> {
                        Field f = ds.fields.get(ds.outputs.get(ns.name));
                        Description de = f.getDeclaredAnnotation(Description.class);

                        TaskDocumentationLanguage.Output output = new TaskDocumentationLanguage.Output(ns.name, de.value());

                        output.type = Arrays.stream(ns.types)
                                .map(TaskDescriptionLanguage.StreamType::name)
                                .collect(Collectors.toList());

                        output.generated = new ArrayList<>();
                        if (ns.generatedColumns != null) {
                            Arrays.stream(ns.generatedColumns)
                                    .forEach(gen -> {
                                        Field fG = ds.fields.get(ds.generated.get(gen));
                                        Description deG = fG.getDeclaredAnnotation(Description.class);

                                        output.generated.add(new TaskDocumentationLanguage.Pair(gen, deG.value()));
                                    });
                        }

                        namedOutputs.add(output);
                    });
        }

        return opDoc;
    }

    public static void adapterDoc(StorageAdapter adapter, FileWriter writer) throws Exception {
        Class<? extends StorageAdapter> adapterClass = adapter.getClass();

        Method proto = adapterClass.getMethod("proto");
        Description d = proto.getDeclaredAnnotation(Description.class);

        String kind = ((adapter instanceof HadoopAdapter) ? "Fallback " : "") + ((adapter instanceof InputAdapter) ? "Input" : "Output");

        TaskDocumentationLanguage.Adapter adapterDoc = new TaskDocumentationLanguage.Adapter(kind, adapterClass.getSimpleName(), d.value());

        adapterDoc.proto = adapter.proto().toString();

        adapterDoc.pkg = adapterClass.getPackage().getName();

        VelocityContext vc = new VelocityContext();
        vc.put("adapter", adapterDoc);

        Template operation = Velocity.getTemplate("adapter.vm", UTF_8.name());
        operation.merge(vc, writer);
    }

    private static List<TaskDocumentationLanguage.Pair> getEnumDocs(Class<? extends Enum<?>> clazz) throws Exception {
        List<TaskDocumentationLanguage.Pair> enDescr = new ArrayList<>();

        for (Enum<?> e : clazz.getEnumConstants()) {
            Description d = e.getClass().getField(e.name()).getDeclaredAnnotation(Description.class);
            enDescr.add(new TaskDocumentationLanguage.Pair(e.name(), d.value()));
        }

        return enDescr;
    }

    public static void writeDoc(TaskDefinitionLanguage.Task exampleTask, Writer writer) throws IOException {
        Properties props = PropertiesConverter.toProperties(exampleTask, true);

        DocumentationGenerator.DocComparator cmp = new DocumentationGenerator.DocComparator();

        Map sm = props.keySet().stream()
                .sorted(cmp)
                .collect(Collectors.toMap(k -> k, props::get, (o, n) -> n, LinkedHashMap::new));

        String kk = null;

        for (Object e : sm.entrySet()) {
            String k = ((Map.Entry<String, Object>) e).getKey();

            if (kk != null) {
                int difference = cmp.compare(k, kk);
                if (difference > 1) {
                    writer.write("\n");
                }
                if (difference > 2) {
                    writer.write("\n");
                }
            }
            writer.write(k + "=" + ((Map.Entry<String, Object>) e).getValue() + "\n");
            kk = k;
        }
    }

    public static class DocComparator implements Comparator<Object> {
        private static Map<String, Integer> PRIORITIES = new HashMap() {
            private int p = 0;

            {
                put(DISTCP_PREFIX);
                put(INPUT_PREFIX);
                put(TASK_INPUT_SINK);
                put(TASK_OPERATIONS);
                put("ds.input.");
                put(OP_OPERATION_PREFIX);
                put(OP_INPUTS_PREFIX);
                put0(OP_INPUT_PREFIX);
                put(OP_DEFINITION_PREFIX);
                put(OP_OUTPUT_PREFIX);
                put0(OP_OUTPUTS_PREFIX);
                put("ds.output.");
                put(OUTPUT_PREFIX);
                put(TASK_TEE_OUTPUT);
            }

            private void put(String prefix) {
                put(prefix, ++p);
            }
            private void put0(String prefix) {
                put(prefix, p);
            }

            public Integer get(Object k) {
                for (Object e : entrySet()) {
                    if (((String) k).startsWith(((Map.Entry<String, Integer>) e).getKey())) {
                        return ((Map.Entry<String, Integer>) e).getValue();
                    }
                }

                return 0;
            }
        };

        @Override
        public int compare(Object o1, Object o2) {
            int d = PRIORITIES.get(o1) - PRIORITIES.get(o2);
            if (d > 0) {
                return 2;
            }
            if (d < 0) {
                return -2;
            }

            d = ((String)o1).compareTo((String)o2);
            if (d > 0) {
                return 1;
            }
            return -1;
        }
    }
}
