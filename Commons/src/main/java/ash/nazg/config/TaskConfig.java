/**
 * Copyright (C) 2020 Locomizer team and Contributors
 * This project uses New BSD license with do no evil clause. For full text, check the LICENSE file in the root directory.
 */
package ash.nazg.config;

import java.util.*;

public class TaskConfig extends PropertiesConfig {
    public static final String DIRECTIVE_SIGIL = "$";

    public static final String TASK_INPUT_SINK = "task.input.sink";
    public static final String TASK_TEE_OUTPUT = "task.tee.output";
    public static final String TASK_OPERATIONS = "task.operations";
    public static final String OP_OPERATION_PREFIX = "op.operation.";

    public Map<String, String> operations;

    private Set<String> inputSink = null;
    private Set<String> teeOutput = null;
    private List<String> opNames = null;

    public Set<String> getInputSink() {
        if (inputSink == null) {
            inputSink = new HashSet<>();
            String[] sinks = getArray(TASK_INPUT_SINK);
            if (sinks != null) {
                inputSink.addAll(Arrays.asList(sinks));
            }
        }

        return inputSink;
    }

    public Set<String> getTeeOutput() {
        if (teeOutput == null) {
            teeOutput = new HashSet<>();
            String[] tees = getArray(TASK_TEE_OUTPUT);
            if (tees != null) {
                teeOutput.addAll(Arrays.asList(tees));
            }
        }

        return teeOutput;
    }

    public List<String> getOpNames() {
        if (opNames == null) {
            opNames = new ArrayList<>();
            String[] names = getArray(TASK_OPERATIONS);
            if (names != null) {
                opNames.addAll(Arrays.asList(names));
            }
        }

        return opNames;
    }

    @Override
    public Properties getProperties() {
        return super.getProperties();
    }

    @Override
    public Properties getOverrides() {
        return super.getOverrides();
    }

    @Override
    public void setProperties(Properties source) {
        super.setProperties(source);
    }

    @Override
    public String getPrefix() {
        String prefix = super.getPrefix();
        return (prefix == null) ? null : prefix.substring(0, prefix.length() - 1);
    }

    @Override
    public void setPrefix(String prefix) {
        super.setPrefix(prefix);
    }

    public Map<String, String> getOperations() {
        if (operations == null) {
            List<String> opNames = getOpNames();

            operations = new LinkedHashMap<>();
            for (String opName : opNames) {
                if (opName.startsWith(DIRECTIVE_SIGIL)) {
                    continue;
                }

                if (operations.containsKey(opName)) {
                    throw new InvalidConfigValueException("Duplicate operation '" + opName + "' in the operations list was encountered for the task");
                }

                String verb = getProperty(OP_OPERATION_PREFIX + opName);
                if (verb == null) {
                    throw new InvalidConfigValueException("Operation named '" + opName + "' has no verb set in the task");
                }

                operations.put(opName, verb);
            }

            if (opNames.isEmpty()) {
                throw new InvalidConfigValueException("Operations were not specified for the task");
            }
        }

        return operations;
    }
}
