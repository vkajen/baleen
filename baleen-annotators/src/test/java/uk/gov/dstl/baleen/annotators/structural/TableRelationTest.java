//Dstl (c) Crown Copyright 2017
package uk.gov.dstl.baleen.annotators.structural;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static uk.gov.dstl.baleen.annotators.structural.TableRelation.PARAM_TYPE;
import static uk.gov.dstl.baleen.annotators.structural.TableRelation.SOURCE_PATTERN;
import static uk.gov.dstl.baleen.annotators.structural.TableRelation.SOURCE_TYPE;
import static uk.gov.dstl.baleen.annotators.structural.TableRelation.TARGET_PATTERN;
import static uk.gov.dstl.baleen.annotators.structural.TableRelation.TARGET_TYPE;

import java.io.IOException;
import java.util.Collection;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.uima.fit.util.JCasUtil;
import org.apache.uima.jcas.tcas.Annotation;
import org.junit.Before;
import org.junit.Test;

import uk.gov.dstl.baleen.annotators.testing.AbstractAnnotatorTest;
import uk.gov.dstl.baleen.types.common.CommsIdentifier;
import uk.gov.dstl.baleen.types.common.Person;
import uk.gov.dstl.baleen.types.semantic.Relation;
import uk.gov.dstl.baleen.types.structure.Document;
import uk.gov.dstl.baleen.types.structure.Paragraph;
import uk.gov.dstl.baleen.types.structure.Table;
import uk.gov.dstl.baleen.types.structure.TableBody;
import uk.gov.dstl.baleen.types.structure.TableCell;
import uk.gov.dstl.baleen.types.structure.TableHeader;
import uk.gov.dstl.baleen.types.structure.TableRow;

public class TableRelationTest extends AbstractAnnotatorTest {

	private static final String TH1 = "Name";
	private static final String TH2 = "eMail";
	private static final String R1C1 = "Stuart";
	private static final String R2C1 = "James";
	private static final String R3C1 = "Chris";
	private static final String R1C2 = "a@b.com";
	private static final String R2C2 = "c@d.com";
	private static final String R3C2 = "d@e.com";
	private static final String HEADING = TH1 + " " + TH2;
	private static final String ROW1 = R1C1 + " " + R1C2;
	private static final String ROW2 = R2C1 + " " + R2C2;
	private static final String ROW3 = R3C1 + " " + R3C2;

	private static final String TEXT = String.join("\n", "", HEADING, ROW1, ROW2, ROW3, " other");

	public TableRelationTest() {
		super(TableRelation.class);
	}

	@Before
	public void setup() throws IOException {
		jCas.setDocumentText(TEXT);

		int cursor = 0;
		int depth = 0;
		Document document = new Document(jCas);
		document.setBegin(cursor);
		document.setDepth(depth);
		document.setEnd(TEXT.length());
		document.addToIndexes();

		Table table = new Table(jCas);
		table.setBegin(cursor);
		table.setDepth(depth);

		TableHeader th = new TableHeader(jCas);
		th.setBegin(cursor);
		th.setDepth(++depth);

		cursor = addRow(depth, cursor, TH1, TH2);

		th.setEnd(cursor);
		th.addToIndexes();
		--depth;

		TableBody tableBody = new TableBody(jCas);
		tableBody.setBegin(cursor);
		tableBody.setDepth(++depth);

		cursor = addRow(depth, cursor, R1C1, R1C2);
		cursor = addRow(depth, cursor, R2C1, R2C2);
		cursor = addRow(depth, cursor, R3C1, R3C2);

		tableBody.setEnd(cursor);
		tableBody.addToIndexes();
		--depth;

		table.setEnd(cursor);
		table.addToIndexes();
		--depth;

		Person chris = new Person(jCas);
		int begin = (HEADING + ROW1 + ROW2).length() + 4;
		chris.setBegin(begin);
		chris.setEnd(begin + R3C1.length());
		chris.addToIndexes();
	}

	private int addRow(int depth, int cursor, String cell1, String cell2) {
		TableRow tableRow = new TableRow(jCas);
		tableRow.setBegin(++cursor);
		tableRow.setDepth(++depth);

		TableCell c1 = new TableCell(jCas);
		c1.setBegin(cursor);
		c1.setDepth(++depth);

		Paragraph p1 = new Paragraph(jCas);
		p1.setBegin(cursor);
		p1.setDepth(++depth);
		cursor += cell1.length();
		p1.setEnd(cursor);
		p1.addToIndexes();

		--depth;
		c1.setEnd(cursor);
		c1.addToIndexes();

		TableCell c2 = new TableCell(jCas);
		c2.setBegin(++cursor);
		c2.setDepth(depth);

		Paragraph p2 = new Paragraph(jCas);
		p2.setBegin(cursor);
		p2.setDepth(++depth);
		cursor += cell2.length();
		p2.setEnd(cursor);
		p2.addToIndexes();
		--depth;

		c2.setEnd(cursor);
		c2.addToIndexes();

		--depth;

		tableRow.setEnd(cursor);
		tableRow.addToIndexes();

		--depth;

		return cursor;
	}

	@Test
	public void testProcess() throws Exception {

		processJCas(SOURCE_PATTERN, "Name", SOURCE_TYPE, Person.class.getSimpleName(), TARGET_PATTERN, "email",
				TARGET_TYPE, CommsIdentifier.class.getSimpleName(), PARAM_TYPE, "emailAddress");

		Collection<Person> people = JCasUtil.select(jCas, Person.class);

		assertEquals(3, people.size());
		Set<String> names = people.stream().map(Annotation::getCoveredText).collect(Collectors.toSet());
		assertTrue(names.contains(R1C1));
		assertTrue(names.contains(R2C1));
		assertTrue(names.contains(R3C1));
		Collection<CommsIdentifier> emails = JCasUtil.select(jCas, CommsIdentifier.class);
		assertEquals(3, emails.size());
		Set<String> addresses = emails.stream().map(Annotation::getCoveredText).collect(Collectors.toSet());
		assertTrue(addresses.contains(R1C2));
		assertTrue(addresses.contains(R2C2));
		assertTrue(addresses.contains(R3C2));

		Collection<Relation> relations = JCasUtil.select(jCas, Relation.class);
		assertEquals(3, relations.size());

		Relation first = relations.iterator().next();
		assertEquals(HEADING.length() + 2, first.getBegin());
		assertEquals(HEADING.length() + 2 + ROW1.length(), first.getEnd());
		assertEquals(Person.class, first.getSource().getClass());
		assertEquals(R1C1, first.getSource().getCoveredText());
		assertEquals(CommsIdentifier.class, first.getTarget().getClass());
		assertEquals(R1C2, first.getTarget().getCoveredText());
		assertEquals("emailAddress", first.getRelationshipType());
	}

}
