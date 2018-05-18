package org.molgenis.data.annotation.makervcf;

import org.molgenis.data.annotation.makervcf.structs.GavinRecord;
import org.molgenis.data.annotation.makervcf.structs.VcfEntity;
import org.molgenis.vcf.VcfInfo;
import org.molgenis.vcf.VcfRecord;
import org.molgenis.vcf.VcfSample;
import org.molgenis.vcf.meta.VcfMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.StreamSupport;

import static java.util.Arrays.stream;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.joining;

/**
 * Maps {@link GavinRecord} to {@link VcfRecord}.
 */
class VcfRecordMapper
{
	private static final String MISSING_VALUE = ".";

	private final VcfMeta vcfMeta;

	VcfRecordMapper(VcfMeta vcfMeta)
	{
		this.vcfMeta = requireNonNull(vcfMeta);
	}

	public VcfRecord map(GavinRecord gavinRecord)
	{
		List<String> tokens = createTokens(gavinRecord);
		return new VcfRecord(vcfMeta, tokens.toArray(new String[0]));
	}

	private List<String> createTokens(GavinRecord gavinRecord)
	{
		VcfEntity vcfEntity = gavinRecord;

		List<String> tokens = new ArrayList<>();
		tokens.add(vcfEntity.getChromosome());
		tokens.add(vcfEntity.getPosition()+"");
		tokens.add(vcfEntity.getId());
		tokens.add(vcfEntity.getRef());
		String[] altTokens = vcfEntity.getAlts();
		if (altTokens.length == 0)
		{
			tokens.add(MISSING_VALUE);
		}
		else
		{
			tokens.add(stream(altTokens).collect(joining(",")));
		}
		String quality = vcfEntity.getQuality();
		tokens.add(quality != null ? quality : MISSING_VALUE);
		String filterStatus = vcfEntity.getFilterStatus();
		tokens.add(filterStatus != null ? filterStatus : MISSING_VALUE);

		tokens.add(createInfoToken(vcfEntity.getVcfEntityInformation()) + ";RLV=" + gavinRecord.getRlv());

//		Iterable<VcfSample> vcfSamples = vcfEntity.getSamples();
//		if (vcfSamples.iterator().hasNext())
//		{
//			tokens.add(createFormatToken(vcfEntity));
//			vcfSamples.forEach(vcfSample -> tokens.add(createSampleToken(vcfSample)));
//		}
		return tokens;
	}

	private String createInfoToken(Iterable<VcfInfo> vcfInformations)
	{
		String infoToken;
		if (vcfInformations.iterator().hasNext())
		{
			infoToken = StreamSupport.stream(vcfInformations.spliterator(), false)
									 .map(this::createInfoTokenPart)
									 .collect(joining(";"));
		}
		else
		{
			infoToken = MISSING_VALUE;
		}
		return infoToken;
	}

	private String createInfoTokenPart(VcfInfo vcfInfo)
	{
		return escapeToken(vcfInfo.getKey()) + '=' + escapeToken(vcfInfo.getValRaw());
	}

	private String createFormatToken(VcfEntity vcfEntity)
	{
		String[] formatTokens = vcfEntity.getFormat();
		return stream(formatTokens).map(this::escapeToken).collect(joining(":"));
	}

	private String createSampleToken(VcfSample vcfSample)
	{
		String[] sampleTokens = vcfSample.getTokens();
		return stream(sampleTokens).map(this::escapeToken).collect(joining(":"));
	}

	/**
	 * TODO ask RK and JvdV: does this apply to v4.2 as well? are we interpreting this correctly?
	 * TODO check if our VcfReader unescapes tokens?
	 * <p>
	 * The Variant Call Format Specification VCFv4.3:
	 * Characters with special meaning (such as field delimiters ’;’ in INFO or ’:’ FORMAT fields) must be represented using the capitalized percent encoding:
	 * %3A : (colon)
	 * %3B ; (semicolon)
	 * %3D = (equal sign)
	 * %25 % (percent sign)
	 * %2C , (comma)
	 * %0D CR
	 * %0A LF
	 * %09 TAB
	 * <p>
	 */
	private String escapeToken(String token)
	{
		if (token == null || token.isEmpty())
		{
			return token;
		}

		StringBuilder stringBuilder = new StringBuilder(token.length());
		for (int i = 0; i < token.length(); ++i)
		{
			char c = token.charAt(i);
			switch (c)
			{
				case ':':
					stringBuilder.append("%3A");
					break;
				case ';':
					stringBuilder.append("%3B");
					break;
				case '=':
					stringBuilder.append("%3D");
					break;
				case '%':
					stringBuilder.append("%25");
					break;
				case ',':
					stringBuilder.append("%2C");
					break;
				case '\r':
					stringBuilder.append("%0D");
					break;
				case '\n':
					stringBuilder.append("%0A");
					break;
				case '\t':
					stringBuilder.append("%09");
					break;
				default:
					stringBuilder.append(c);
					break;
			}
		}
		return stringBuilder.toString();
	}
}