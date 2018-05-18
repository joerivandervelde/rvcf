package org.molgenis.data.annotation.makervcf.positionalstream;

import org.molgenis.data.annotation.makervcf.structs.Relevance;
import org.molgenis.data.annotation.makervcf.structs.RelevantVariant;

import java.util.Iterator;

/**
 * Created by joeri on 6/29/16.
 *
 */
public class MAFFilter {

    private Iterator<RelevantVariant> relevantVariants;
    double threshold = 0.05;
    boolean verbose;

    public MAFFilter(Iterator<RelevantVariant> relevantVariants, boolean verbose)
    {
        this.relevantVariants = relevantVariants;
        this.verbose = verbose;
    }

    public Iterator<RelevantVariant> go()
    {
        return new Iterator<RelevantVariant>(){

            RelevantVariant nextResult;

            @Override
            public boolean hasNext() {
                    while (relevantVariants.hasNext()) {
                        RelevantVariant rv = relevantVariants.next();

                        for(Relevance rlv : rv.getRelevance())
                        {
                            //use GoNL/ExAC MAF to control for false positives (or non-relevant stuff) in ClinVar
                            if(rlv.getGonlAlleleFreq() < threshold && rlv.getAlleleFreq() < threshold)
                            {
                                nextResult = rv;
                                return true;
                            }
                            else if(verbose)
                            {
                                if(verbose){ System.out.println("[MAFFilter] Removing variant at " +rv.getVariant().getChromosome() +":"+rv.getVariant().getPosition() + " because it has AF >"+threshold+". ExAC: "+rlv.getAlleleFreq()+", GoNL: "+rlv.getGonlAlleleFreq()+""); }
                            }
                        }

                    }
                    return false;
            }

            @Override
            public RelevantVariant next() {
                return nextResult;
            }
        };
    }
}
