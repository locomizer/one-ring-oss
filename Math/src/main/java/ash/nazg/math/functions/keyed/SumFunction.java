/**
 * Copyright (C) 2020 Locomizer team and Contributors
 * This project uses New BSD license with do no evil clause. For full text, check the LICENSE file in the root directory.
 */
package ash.nazg.math.functions.keyed;

import java.util.List;

public class SumFunction extends KeyedFunction {
    public SumFunction(Double _const) {
        super(_const);
    }

    @Override
    public Double calcSeries(List<Double> series) {
        double result = (_const != null) ? _const : 0.D;

        for (Double value : series) {
            result += value;
        }

        return result;
    }
}
