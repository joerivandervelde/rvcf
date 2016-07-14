package org.molgenis.data.annotation.makervcf.positionalstream;

import org.apache.commons.lang.StringUtils;
import org.molgenis.cgd.CGDEntry;
import org.molgenis.cgd.LoadCGD;
import org.molgenis.data.Entity;
import org.molgenis.data.annotation.makervcf.structs.RelevantVariant;
import org.molgenis.cgd.CGDEntry.generalizedInheritance;
import org.molgenis.data.annotation.makervcf.structs.VcfEntity;

import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * Created by joeri on 6/1/16.
 *
 * Take the output of DiscoverRelevantVariants, re-iterate over the original VCF file, but this time check the genotypes.
 * We want to match genotypes to disease inheritance mode, ie. dominant/recessive acting.
 *
 */
public class MatchVariantsToGenotypeAndInheritance {

    Iterator<RelevantVariant> relevantVariants;
    Map<String, CGDEntry> cgd;
    int minDepth;
    boolean verbose;

    public enum status{
        HETEROZYGOUS, HOMOZYGOUS, AFFECTED, CARRIER, BLOODGROUP, HOMOZYGOUS_COMPOUNDHET, AFFECTED_COMPOUNDHET, HETEROZYGOUS_MULTIHIT;

        public static boolean isCompound(status status){
            return (status == HOMOZYGOUS_COMPOUNDHET || status == AFFECTED_COMPOUNDHET) ? true : false;}

        public static boolean isPresumedCarrier(status status){
            return (status == HETEROZYGOUS || status == HETEROZYGOUS_MULTIHIT || status == CARRIER) ? true : false;}

        public static boolean isPresumedAffected(status status){
            return (status == HOMOZYGOUS || status == HOMOZYGOUS_COMPOUNDHET || status == AFFECTED || status == AFFECTED_COMPOUNDHET) ? true : false;}

    }

    public MatchVariantsToGenotypeAndInheritance(Iterator<RelevantVariant> relevantVariants, File cgdFile, boolean verbose) throws IOException
    {
        this.relevantVariants = relevantVariants;
        this.cgd = LoadCGD.loadCGD(cgdFile);
        this.minDepth = 1;
        this.verbose = verbose;
    }


    public Iterator<RelevantVariant > go() throws Exception {

        return new Iterator<RelevantVariant>(){


            @Override
            public boolean hasNext() {
                return relevantVariants.hasNext();
            }

            @Override
            public RelevantVariant next() {


                try {
                    RelevantVariant rv = relevantVariants.next();

                    String gavinGene = rv.getGavinJudgment() != null ? (rv.getGavinJudgment().getGene() != null ? rv.getGavinJudgment().getGene() : null) : null;
                    String clinvarGene = rv.getClinvarJudgment().getGene() != null ? rv.getClinvarJudgment().getGene() : null;

                    //extra checks that things are okay
                    if(gavinGene != null && clinvarGene != null && !gavinGene.equals(clinvarGene))
                    {
                        throw new Exception("Conflicting genes passed to MatchVariantsToGenotypeAndInheritance: " + gavinGene + " vs " + clinvarGene);
                    }
                    if(gavinGene == null && clinvarGene == null)
                    {
                        throw new Exception("No genes passed to MatchVariantsToGenotypeAndInheritance!");
                    }

                    String gene = gavinGene != null ? gavinGene : clinvarGene;


                    CGDEntry ce = cgd.get(gene);
                    generalizedInheritance inh = ce != null ? ce.getGeneralizedInheritance() : generalizedInheritance.NOTINCGD;
                    HashMap<String, Entity> affectedSamples = findMatchingSamples(rv.getVariant(), rv.getAllele(), inh, true);
                    HashMap<String, Entity> carrierSamples = findMatchingSamples(rv.getVariant(), rv.getAllele(), inh, false);

                    rv.setCgdInfo(ce);

                    status actingTerminology = status.HOMOZYGOUS;
                    status nonActingTerminology = status.HETEROZYGOUS;

                    // regular inheritance types, recessive and/or dominant or some type, we use affected/carrier because we know how the inheritance acts
                    // females can be X-linked carriers, though since X is inactivated, they might be (partly) affected
                    if (cgd.containsKey(gene) && (generalizedInheritance.hasKnownInheritance(cgd.get(gene).getGeneralizedInheritance())))
                    {
                        actingTerminology = status.AFFECTED;
                        nonActingTerminology = status.CARRIER;
                    }
                    //TODO: handle blood group marker information? not pathogenic but still valuable?
//                    else if (cgd.containsKey(gene) && (cgd.get(gene).getGeneralizedInheritance() == generalizedInheritance.BLOODGROUP))
//                    {
//                        actingTerminology = status.BLOODGROUP;
//                        nonActingTerminology = status.BLOODGROUP;
//                    }

                    Map<String, status> sampleStatus = new HashMap<>();
                    Map<String, String> sampleGenotypes = new HashMap<>();

                    for(String key : affectedSamples.keySet())
                    {
                        sampleStatus.put(key, actingTerminology);
                        sampleGenotypes.put(key, affectedSamples.get(key).get("GT").toString());
                    }
                    for(String key : carrierSamples.keySet())
                    {
                        sampleStatus.put(key, nonActingTerminology);
                        sampleGenotypes.put(key, carrierSamples.get(key).get("GT").toString());
                    }
                    rv.setSampleStatus(sampleStatus);
                    rv.setSampleGenotypes(sampleGenotypes);

                    if(verbose) {
                        System.out.println("assigned sample status: " + sampleStatus.toString());}

                    return rv;
                }
                catch(Exception e)
                {
                    throw new RuntimeException(e);
                }

            }
        };
    }

    /**
     * TODO: compound heterozygous
     * TODO: trio filter
     * @param record
     * @param alt
     * @param inheritance
     * @param lookingForAffected
     * @return
     * @throws Exception
     */
    public HashMap<String, Entity> findMatchingSamples(VcfEntity record, String alt, generalizedInheritance inheritance, boolean lookingForAffected) throws Exception {
        int altIndex = VcfEntity.getAltAlleleIndex(record, alt);
        HashMap<String, Entity> matchingSamples = new HashMap<>();

        for (Entity sample : record.getSamples())
        {
            if(sample.get("GT") == null)
            {
                continue;
            }

            String genotype = sample.get("GT").toString();
            String sampleName = sample.get("ORIGINAL_NAME").toString();

            if (genotype.equals("./.") || genotype.equals(".") || genotype.equals(".|."))
            {
                continue;
            }

            // quality filter: we want depth X or more, if available
            if(sample.get("DP") != null)
            {
                int depthOfCoverage = Integer.parseInt(sample.get("DP").toString());
                if (depthOfCoverage < minDepth)
                {
                    continue;
                }
            }

            //now that everything is okay, we can match to inheritance mode

            //all dominant types, so no carriers, and only requirement is that genotype contains 1 alt allele somewhere
            if(inheritance.equals(generalizedInheritance.DOMINANT_OR_RECESSIVE) || inheritance.equals(generalizedInheritance.DOMINANT))
            {
                // 1 or more, so works for hemizygous too
                if ( genotype.contains(altIndex+"") && lookingForAffected )
                {
                    matchingSamples.put(sampleName, sample);
                }
            }

            //all other types, unknown, complex or recessive
            //for recessive we know if its acting or not, but this is handled in the terminology of a homozygous hit being labeled as 'AFFECTED'
            //for other (digenic, maternal, YL, etc) and not-in-CGD we don't know, but we still report homozygous as 'acting' and heterozygous as 'carrier' to make that distinction
            else if(inheritance.equals(generalizedInheritance.RECESSIVE) || inheritance.equals(generalizedInheritance.XL_LINKED)
                    || inheritance.equals(generalizedInheritance.OTHER) || inheritance.equals(generalizedInheritance.NOTINCGD)
                    || inheritance.equals(generalizedInheritance.BLOODGROUP))
            {
                boolean homozygous = genotype.equals(altIndex + "/" + altIndex) || genotype.equals(altIndex + "|" + altIndex);
                boolean hemizygous = genotype.equals(altIndex+"");
                boolean heterozygous = genotype.length() == 3 && StringUtils.countMatches(genotype, altIndex+"") == 1;

                // regular homozygous
                if ( lookingForAffected && homozygous )
                {
                    matchingSamples.put(sampleName, sample);
                }
                //for hemizygous, 1 allele is enough of course
                else if( lookingForAffected && hemizygous )
                {
                    matchingSamples.put(sampleName, sample);
                }
                // heterozygous, ie. carriers when disease is recessive
                else if ( !lookingForAffected && heterozygous )
                {
                    matchingSamples.put(sampleName, sample);
                }

            }
            else
            {
                throw new Exception("inheritance unknown: " + inheritance);
            }

        }
        return matchingSamples;
    }
}