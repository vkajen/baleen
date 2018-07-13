//Dstl (c) Crown Copyright 2017
package uk.gov.dstl.baleen.annotators.regex.internals;

import java.util.Collections;
import java.util.regex.Matcher;

import org.apache.commons.lang3.StringUtils;
import org.apache.uima.jcas.JCas;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableSet;

import uk.gov.dstl.baleen.annotators.helpers.QuantityUtils;
import uk.gov.dstl.baleen.annotators.regex.helpers.AbstractRegexAnnotator;
import uk.gov.dstl.baleen.core.pipelines.orderers.AnalysisEngineAction;
import uk.gov.dstl.baleen.exceptions.BaleenException;
import uk.gov.dstl.baleen.types.common.Money;

/**
 * Identifies money quantities using regular expressions.
 * 
 * Where a currency symbol is used for multiple different currencies, e.g. $ for USD and AUD, the most common is selected (e.g. USD).
 * Symbols for the top 10 most traded currencies are supported, as are all ISO 4217 codes.
 * Where symbols are text (e.g. Fr for Swiss Francs), they are case sensitive.
 */
public class MoneyRegex extends AbstractRegexAnnotator<Money> {
	private static final String CURRENCY_CODES = "AED|AFN|ALL|AMD|ANG|AOA|ARS|AUD|AWG|AZN|"
			+ "BAM|BBD|BDT|BGN|BHD|BIF|BMD|BND|BOB|BOV|BRL|BSD|BTN|BWP|BYR|BZD|"
			+ "CAD|CDF|CHE|CHF|CHW|CLF|CLP|CNY|COP|COU|CRC|CUC|CUP|CVE|CZK|"
			+ "DJF|DKK|DOP|DZD|"
			+ "EGP|ERN|ETB|EUR|"
			+ "FJD|FKP|"
			+ "GBP|GEL|GHS|GIP|GMD|GNF|GTQ|GYD|"
			+ "HKD|HNL|HRK|HTG|HUF|"
			+ "IDR|ILS|INR|IQD|IRR|ISK|"
			+ "JMD|JOD|JPY|"
			+ "KES|KGS|KHR|KMF|KPW|KRW|KWD|KYD|KZT|"
			+ "LAK|LBP|LKR|LRD|LSL|LYD|"
			+ "MAD|MDL|MGA|MKD|MMK|MNT|MOP|MRO|MUR|MVR|MWK|MXN|MXV|MYR|MZN|"
			+ "NAD|NGN|NIO|NOK|NPR|NZD|"
			+ "OMR|"
			+ "PAB|PEN|PGK|PHP|PKR|PLN|PYG|"
			+ "QAR|"
			+ "RON|RSD|RUB|RWF|"
			+ "SAR|SBD|SCR|SDG|SEK|SGD|SHP|SLL|SOS|SRD|SSP|STD|SYP|SZL|"
			+ "THB|TJS|TMT|TND|TOP|TRY|TTD|TWD|TZS|"
			+ "UAH|UGX|USD|USN|USS|UYI|UYU|UZS|"
			+ "VEF|VND|VUV|"
			+ "WST|"
			+ "XAF|XAG|XAU|XBA|XBB|XBC|XBD|XCD|XDR|XFU|XOF|XPD|XPF|XPT|XSU|XTS|XUA|XXX|"
			+ "YER|"
			+ "ZAR|ZMW";
	private static final String CURRENCY_SYMBOLS = "£|\\$|€|¥|Fr";
	private static final String CURRENCY_SYMBOLS_FRACTIONS = "p|¢|c";
	private static final String MULTIPLIERS = "k|thousand|million|m|billion|b|trillion|t";
	private static final String WHITESPACE = "\\h*";
	private static final String START = "(?<=^|\\(|\\s)";
	private static final String END = "(?=$|\\)|\\?|\\!|\\s|[\\.,](\\s|$))";
	
	private static final String MONEY_REGEX = START+"("+CURRENCY_CODES+"|"+CURRENCY_SYMBOLS+")?("+WHITESPACE+"([0-9]+([,\\. ][0-9]{3})*([,.][0-9]+)?))("+WHITESPACE+"("+MULTIPLIERS+"))?("+WHITESPACE+"("+CURRENCY_CODES+"|"+CURRENCY_SYMBOLS+"|"+CURRENCY_SYMBOLS_FRACTIONS+"))?("+WHITESPACE+"("+MULTIPLIERS+"))?"+END;
	
	/** 
	 * New instance.
	 */
	public MoneyRegex() {
		super(MONEY_REGEX, false, 1.0f);
	}

	@Override
	protected Money create(JCas jCas, Matcher matcher) {
		if(Strings.isNullOrEmpty(matcher.group(1)) && Strings.isNullOrEmpty(matcher.group(9))){
			//Must find at least one currency token
			return null;
		}
		
		//Edge case to remove times being detected
		if(matcher.group().toLowerCase().matches("([0-1]?[0-9]|2[0-4])\\.[0-5][0-9]\\s*pm")){
			return null;
		}
		
		//First, work out the number and parse it to a Double
		String numbers = matcher.group(3).replaceAll("\\s", "");
		
		numbers = correctCommasAndDecimals(numbers);
		if(numbers == null){
			return null;
		}
		
		//Then apply any multipliers
		Double value = applyMultipliers(matcher, Double.parseDouble(numbers));
		
		//Now work out the currency
		String currency = "";
		if(!Strings.isNullOrEmpty(matcher.group(1))){
			currency = matcher.group(1);
		}else{
			//We've already checked that we have a symbol, so it must be in group 9
			currency = matcher.group(9);
		}
		
		return createMoney(jCas, currency, value);
	}
	
	private boolean isCommaDecimal(Integer commaCount, Integer periodCount, String numbers){
		if(commaCount == 1 && periodCount > 1){
			//Only one comma and multiple periods, so comma must be decimal
			return true;
		}
		
		if(periodCount == 1 && commaCount == 1 && numbers.indexOf(',') > numbers.indexOf('.')){
			//Only one comma and one period, but the comma is after the period so must be the decimal
			return true;
		}
		
		return false;
	}
	
	private boolean isPeriodDecimal(Integer commaCount, Integer periodCount, String numbers){
		if(periodCount == 1 && commaCount > 1){
			//Only one period and multiple commas, so period must be decimal
			return true;
		}
		
		if(periodCount == 1 && commaCount == 1 && numbers.indexOf(',') < numbers.indexOf('.')){
			//Only one period and one comma, but the period is after the comma so must be the decimal
			return true;
		}
		
		return false;
	}
	
	private String processCommasAndDecimals(String text, Integer commaCount, Integer periodCount) throws BaleenException{
		String numbers = text;
		
		//Check we don't have alternating periods and commas
		if(numbers.matches(".*,.*\\..*,.*") || numbers.matches(".*\\..*,.*\\..*")){
			getMonitor().warn("Unable to parse monetary value '{}', as it contains alternating commas and periods", numbers);
			throw new BaleenException("Value contains alternating commas and periods");
		}
		
		//We have commas and periods, so work out which is being used for what
		if(isCommaDecimal(commaCount, periodCount, numbers)){
			//Using comma as the decimal, and period as thousands separator
			numbers = numbers.replaceAll("\\.", "");
			numbers = numbers.replaceAll(",", ".");
		}else if(isPeriodDecimal(commaCount, periodCount, numbers)){
			//Using period as the decimal, and commas as thousands separator
			numbers = numbers.replaceAll(",", "");
		}else{
			//We have multiple commas and multiple decimals
			getMonitor().warn("Unable to parse monetary value '{}', as it contains multiple commas and periods", numbers);
			throw new BaleenException("Value contains multiple commas and periods");
		}
		
		return numbers;
	}
	
	/**
	 * Take a number (as a string), and try to work out whether commas/periods are being used as decimals or thousand separators
	 * Returns a corrected string, with no thousand separators and a period for the decimal.
	 */
	public String correctCommasAndDecimals(String number){
		String correctedNumber = number;
		
		Integer commaCount = StringUtils.countMatches(correctedNumber, ',');
		Integer periodCount = StringUtils.countMatches(correctedNumber, '.');
		
		if(commaCount > 0 && periodCount > 0){
			try{
				correctedNumber = processCommasAndDecimals(correctedNumber, commaCount, periodCount);
			}catch(BaleenException be){
				getMonitor().warn("Unable to parse monetary value '{}'", be);
				return null;
			}
		}else if(commaCount > 1){
			//Using comma as a thousands separator
			correctedNumber = correctedNumber.replaceAll(",", "");
		}else if(commaCount == 1){
			String[] parts = correctedNumber.split(",");
			if(parts[1].length() == 3){
				//Probably using comma as a thousands separator
				correctedNumber = correctedNumber.replaceAll(",", "");
			}else{
				//Probably using comma as a decimal
				correctedNumber = correctedNumber.replaceAll(",", ".");
			}
		}else if(periodCount > 1){
			//Using period as a thousands separator
			correctedNumber = correctedNumber.replaceAll("\\.", "");
		}else if(periodCount == 1){
			String[] parts = correctedNumber.split("\\.");
			if(parts[1].length() == 3){
				//Probably using period as a thousands separator
				correctedNumber = correctedNumber.replaceAll("\\.", "");
			}
			//Else, probably using period as a decimal and so we don't need to do anything
		}
		
		return correctedNumber;
	}
	
	private Double applyMultipliers(Matcher matcher, Double value){
		Double val = value;
		if(!Strings.isNullOrEmpty(matcher.group(7))){
			val = QuantityUtils.scaleByMultipler(val, matcher.group(7));
		}
		if(!Strings.isNullOrEmpty(matcher.group(11))){
			val = QuantityUtils.scaleByMultipler(val, matcher.group(11));
		}
		
		return val;
	}
	
	private Money createMoney(JCas jCas, String currency, Double value){
		Money m = new Money(jCas);
		Double val = value;
		
		switch(currency){
		case "p":
			val /= 100;
			m.setCurrency("GBP");
			break;
		case "£":
			m.setCurrency("GBP");
			break;
		case "¢":
			val /= 100;
			m.setCurrency("USD");
			break;
		case "$":
			m.setCurrency("USD");
			break;
		case "c":
			val /= 100;
			m.setCurrency("EUR");
			break;
		case "€":
			m.setCurrency("EUR");
			break;
		case "¥":
			m.setCurrency("JPY");
			break;
		case "Fr":
			m.setCurrency("CHF");
			break;
		default:
			if(CURRENCY_CODES.contains(currency.toUpperCase())){
				m.setCurrency(currency.toUpperCase());
			}
		}
		
		m.setAmount(val);
		
		return m;
	}
	
	@Override
	public AnalysisEngineAction getAction() {
		return new AnalysisEngineAction(Collections.emptySet(), ImmutableSet.of(Money.class));
	}
}