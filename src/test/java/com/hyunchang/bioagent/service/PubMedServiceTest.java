package com.hyunchang.bioagent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyunchang.bioagent.dto.AbstractSection;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PubMedService의 외부 호출이 없는 순수 로직(파서, term 빌더, 정렬 매퍼)에 대한 단위 테스트.
 * Spring 컨텍스트 없이 직접 인스턴스화 가능.
 */
class PubMedServiceTest {

    private PubMedService newService() {
        // 테스트하는 메서드들은 RestClient를 사용하지 않으므로 빈 클라이언트로 충분.
        return new PubMedService(RestClient.create(), new ObjectMapper());
    }

    // ── mapSortParam ────────────────────────────────────────────────────

    @Test
    void mapSortParam_relevanceAndNullReturnNull() {
        PubMedService s = newService();
        assertNull(s.mapSortParam(null));
        assertNull(s.mapSortParam(""));
        assertNull(s.mapSortParam("relevance"));
        assertNull(s.mapSortParam("RELEVANCE"));
    }

    @Test
    void mapSortParam_pubDateMapsToPubDate() {
        assertEquals("pub_date", newService().mapSortParam("pubDate"));
    }

    @Test
    void mapSortParam_epubDateMapsToDate() {
        assertEquals("date", newService().mapSortParam("epubDate"));
    }

    @Test
    void mapSortParam_unknownReturnsNull() {
        assertNull(newService().mapSortParam("unsupported"));
    }

    // ── buildFilteredTerm ────────────────────────────────────────────────

    @Test
    void buildFilteredTerm_baseQueryOnly() {
        String t = newService().buildFilteredTerm("BRCA2", null, false, null, null);
        assertEquals("BRCA2", t);
    }

    @Test
    void buildFilteredTerm_addsPublicationType() {
        String t = newService().buildFilteredTerm("BRCA2", "Review", false, null, null);
        assertTrue(t.contains("\"Review\"[Publication Type]"), t);
    }

    @Test
    void buildFilteredTerm_addsPmcFilter() {
        String t = newService().buildFilteredTerm("BRCA2", null, true, null, null);
        assertTrue(t.contains("\"pubmed pmc\"[sb]"), t);
    }

    @Test
    void buildFilteredTerm_addsYearRange() {
        String t = newService().buildFilteredTerm("BRCA2", null, false, 2020, 2025);
        assertTrue(t.contains("(\"2020\"[dp] : \"2025\"[dp])"), t);
    }

    @Test
    void buildFilteredTerm_yearFromOnly_usesUpperSentinel() {
        String t = newService().buildFilteredTerm("BRCA2", null, false, 2020, null);
        assertTrue(t.contains("\"2020\"[dp]"), t);
        assertTrue(t.contains("\"3000\"[dp]"), t);
    }

    @Test
    void buildFilteredTerm_yearToOnly_usesLowerSentinel() {
        String t = newService().buildFilteredTerm("BRCA2", null, false, null, 2025);
        assertTrue(t.contains("\"1900\"[dp]"), t);
        assertTrue(t.contains("\"2025\"[dp]"), t);
    }

    @Test
    void buildFilteredTerm_combinesAllFilters() {
        String t = newService().buildFilteredTerm("BRCA2", "Clinical Trial", true, 2020, 2025);
        assertTrue(t.startsWith("BRCA2"));
        assertTrue(t.contains("\"Clinical Trial\"[Publication Type]"));
        assertTrue(t.contains("\"pubmed pmc\"[sb]"));
        assertTrue(t.contains("(\"2020\"[dp] : \"2025\"[dp])"));
    }

    // ── parseAbstractSections ────────────────────────────────────────────

    @Test
    void parseAbstractSections_emptyOrNullReturnsEmpty() {
        PubMedService s = newService();
        assertTrue(s.parseAbstractSections(null).isEmpty());
        assertTrue(s.parseAbstractSections("").isEmpty());
        assertTrue(s.parseAbstractSections("   ").isEmpty());
    }

    @Test
    void parseAbstractSections_unstructuredAbstract_singleEntry_noLabel() {
        String xml = """
                <PubmedArticleSet>
                  <PubmedArticle>
                    <MedlineCitation>
                      <Article>
                        <Abstract>
                          <AbstractText>BRCA2 mutations are associated with hereditary breast cancer.</AbstractText>
                        </Abstract>
                      </Article>
                    </MedlineCitation>
                  </PubmedArticle>
                </PubmedArticleSet>
                """;
        List<AbstractSection> sections = newService().parseAbstractSections(xml);
        assertEquals(1, sections.size());
        assertNull(sections.get(0).getLabel());
        assertTrue(sections.get(0).getText().contains("BRCA2"));
    }

    @Test
    void parseAbstractSections_structuredAbstract_keepsLabelAndOrder() {
        String xml = """
                <PubmedArticleSet>
                  <PubmedArticle>
                    <MedlineCitation>
                      <Article>
                        <Abstract>
                          <AbstractText Label="BACKGROUND">Context info.</AbstractText>
                          <AbstractText Label="METHODS">Methodology used.</AbstractText>
                          <AbstractText Label="RESULTS">Findings here.</AbstractText>
                          <AbstractText Label="CONCLUSIONS">Final remarks.</AbstractText>
                        </Abstract>
                      </Article>
                    </MedlineCitation>
                  </PubmedArticle>
                </PubmedArticleSet>
                """;
        List<AbstractSection> sections = newService().parseAbstractSections(xml);
        assertEquals(4, sections.size());
        assertEquals("BACKGROUND", sections.get(0).getLabel());
        assertEquals("METHODS", sections.get(1).getLabel());
        assertEquals("RESULTS", sections.get(2).getLabel());
        assertEquals("CONCLUSIONS", sections.get(3).getLabel());
        assertEquals("Context info.", sections.get(0).getText());
    }

    @Test
    void parseAbstractSections_skipsEmptySections() {
        String xml = """
                <PubmedArticleSet>
                  <PubmedArticle>
                    <MedlineCitation>
                      <Article>
                        <Abstract>
                          <AbstractText Label="BACKGROUND">Real text.</AbstractText>
                          <AbstractText Label="EMPTY"></AbstractText>
                        </Abstract>
                      </Article>
                    </MedlineCitation>
                  </PubmedArticle>
                </PubmedArticleSet>
                """;
        List<AbstractSection> sections = newService().parseAbstractSections(xml);
        assertEquals(1, sections.size());
        assertEquals("BACKGROUND", sections.get(0).getLabel());
    }

    @Test
    void parseAbstractSections_collapsesWhitespace() {
        String xml = """
                <PubmedArticleSet>
                  <PubmedArticle>
                    <MedlineCitation>
                      <Article>
                        <Abstract>
                          <AbstractText>Line one

                          line two   with    spaces.</AbstractText>
                        </Abstract>
                      </Article>
                    </MedlineCitation>
                  </PubmedArticle>
                </PubmedArticleSet>
                """;
        List<AbstractSection> sections = newService().parseAbstractSections(xml);
        assertEquals(1, sections.size());
        // 다중 공백·개행이 단일 공백으로 정규화되었는지 확인
        assertFalse(sections.get(0).getText().contains("  "));
        assertFalse(sections.get(0).getText().contains("\n"));
    }

    @Test
    void parseAbstractSections_malformedXmlReturnsEmpty() {
        List<AbstractSection> sections = newService().parseAbstractSections("<not valid xml");
        assertTrue(sections.isEmpty());
    }
}
