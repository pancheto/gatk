/*
 * Copyright (c) 2010.
 *
 * Permission is hereby granted, free of charge, to any person
 * obtaining a copy of this software and associated documentation
 * files (the "Software"), to deal in the Software without
 * restriction, including without limitation the rights to use,
 * copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the
 * Software is furnished to do so, subject to the following
 * conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES
 * OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT
 * HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 * WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
 * FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR
 * THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package org.broadinstitute.sting.gatk.walkers.genotyper;

import org.apache.log4j.Logger;
import org.broadinstitute.sting.utils.variantcontext.Allele;
import org.broadinstitute.sting.utils.variantcontext.GenotypesContext;

import java.io.PrintStream;
import java.util.List;


/**
 * The model representing how we calculate a genotype given the priors and a pile
 * of bases and quality scores
 */
public abstract class AlleleFrequencyCalculationModel implements Cloneable {

    public enum Model {
        /** The default model with the best performance in all cases */
        EXACT,
    }

    protected int N;

    protected Logger logger;
    protected PrintStream verboseWriter;

    protected enum GenotypeType { AA, AB, BB }

    protected static final double VALUE_NOT_CALCULATED = Double.NEGATIVE_INFINITY;

    protected AlleleFrequencyCalculationModel(UnifiedArgumentCollection UAC, int N, Logger logger, PrintStream verboseWriter) {
        this.N = N;
        this.logger = logger;
        this.verboseWriter = verboseWriter;
    }

    /**
     * Must be overridden by concrete subclasses
     * @param GLs                               genotype likelihoods
     * @param Alleles                           Alleles corresponding to GLs
     * @param log10AlleleFrequencyPriors        priors
     * @param result                            (pre-allocated) object to store likelihoods results
     */
    protected abstract void getLog10PNonRef(GenotypesContext GLs,  List<Allele> Alleles,
                                            double[][] log10AlleleFrequencyPriors,
                                            AlleleFrequencyCalculationResult result);
}