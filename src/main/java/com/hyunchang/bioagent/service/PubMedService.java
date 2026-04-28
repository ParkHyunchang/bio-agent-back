package com.hyunchang.bioagent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyunchang.bioagent.dto.PaperDetail;
import com.hyunchang.bioagent.dto.PaperSummary;
import com.hyunchang.bioagent.dto.SearchResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class PubMedService {

    private static final String BASE_URL = "https://eutils.ncbi.nlm.nih.gov/entrez/eutils";

    private final RestClient pubmedRestClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final int TOO_BROAD_THRESHOLD = 200;
    /** PMC 본문이 너무 길어 Claude 컨텍스트를 과다 점유하지 않도록 컷오프. */
    private static final int MAX_FULL_TEXT_CHARS = 150_000;
    /** JATS XML에서 단락/섹션 구분 역할을 하는 블록 요소들. */
    private static final Set<String> BLOCK_ELEMENTS = Set.of(
            "sec", "p", "title", "abstract", "list-item", "caption", "table-wrap"
    );

    /** UI 정렬값 → PubMed esearch sort 파라미터. relevance/null이면 기본(best match). */
    private String mapSortParam(String sort) {
        if (sort == null || sort.isBlank() || "relevance".equalsIgnoreCase(sort)) return null;
        if ("pubDate".equalsIgnoreCase(sort)) return "pub_date";
        if ("epubDate".equalsIgnoreCase(sort)) return "date";
        return null;
    }

    /** 사용자 쿼리에 publication type / PMC 필터를 PubMed term 문법으로 덧붙인다. */
    private String buildFilteredTerm(String baseQuery, String pubType, boolean onlyPmc) {
        StringBuilder term = new StringBuilder(baseQuery);
        if (pubType != null && !pubType.isBlank()) {
            term.append(" AND \"").append(pubType).append("\"[Publication Type]");
        }
        if (onlyPmc) {
            term.append(" AND \"pubmed pmc\"[sb]");
        }
        return term.toString();
    }

    /** 429 Rate Limit 시 1초 대기 후 1회 재시도하는 GET 헬퍼 */
    private String fetchWithRetry(URI uri) throws Exception {
        try {
            return pubmedRestClient.get().uri(uri).retrieve().body(String.class);
        } catch (Exception e) {
            if (e.getMessage() != null && e.getMessage().contains("429")) {
                log.warn("PubMed rate limit 감지, 1.1초 대기 후 재시도");
                Thread.sleep(1100);
                return pubmedRestClient.get().uri(uri).retrieve().body(String.class);
            }
            throw e;
        }
    }

    /** espell API로 PubMed 철자 교정어 반환. 교정 불필요시 null */
    private String checkSpelling(String query) {
        URI spellUri = UriComponentsBuilder.fromUriString(BASE_URL + "/espell.fcgi")
                .queryParam("db", "pubmed")
                .queryParam("term", query)
                .queryParam("retmode", "json")
                .build().toUri();
        try {
            String response = pubmedRestClient.get().uri(spellUri).retrieve().body(String.class);
            String corrected = objectMapper.readTree(response)
                    .path("esearchresult").path("correctedquery").asText("");
            if (!corrected.isBlank() && !corrected.equalsIgnoreCase(query)) {
                return corrected;
            }
        } catch (Exception e) {
            log.debug("PubMed espell 생략 ({}): {}", e.getClass().getSimpleName(), e.getMessage());
        }
        return null;
    }

    public SearchResponse search(String query, int page, int size) {
        return search(query, page, size, null, null, false);
    }

    /**
     * @param sort     null/"relevance" → best match, "pubDate" → 정식 발행일순,
     *                 "epubDate" → PubMed 등록순(sort=date, 온라인 공개순 근사)
     * @param pubType  Publication Type 필터 (예: "Review", "Clinical Trial"). 빈/널이면 무시.
     * @param onlyPmc  PMC 본문 보유 논문만(`"pubmed pmc"[sb]`).
     */
    public SearchResponse search(String query, int page, int size,
                                 String sort, String pubType, boolean onlyPmc) {
        String correctedQuery = checkSpelling(query);
        String baseQuery = correctedQuery != null ? correctedQuery : query;
        String searchQuery = buildFilteredTerm(baseQuery, pubType, onlyPmc);

        int retStart = (page - 1) * size;

        // 1. esearch: 총 건수 확인 및 현재 페이지 PMID 조회
        UriComponentsBuilder esearchBuilder = UriComponentsBuilder.fromUriString(BASE_URL + "/esearch.fcgi")
                .queryParam("db", "pubmed")
                .queryParam("term", searchQuery)
                .queryParam("retstart", retStart)
                .queryParam("retmax", size)
                .queryParam("retmode", "json");
        String sortParam = mapSortParam(sort);
        if (sortParam != null) {
            esearchBuilder.queryParam("sort", sortParam);
        }
        URI searchUri = esearchBuilder.build().toUri();

        List<String> pmids = new ArrayList<>();
        int totalCount = 0;
        try {
            String searchResponse = fetchWithRetry(searchUri);
            JsonNode esearchResult = objectMapper.readTree(searchResponse).path("esearchresult");
            totalCount = esearchResult.path("count").asInt(0);
            for (JsonNode id : esearchResult.path("idlist")) {
                pmids.add(id.asText());
            }
        } catch (Exception e) {
            log.warn("PubMed esearch 실패 (query='{}'): {}", query, e.getMessage());
            return SearchResponse.builder()
                    .papers(List.of()).total(0).page(page).size(size).tooBroad(false).build();
        }

        if (pmids.isEmpty()) {
            return SearchResponse.builder()
                    .papers(List.of()).total(totalCount).page(page).size(size)
                    .tooBroad(totalCount > TOO_BROAD_THRESHOLD)
                    .correctedQuery(correctedQuery).build();
        }

        // 2. esummary: 제목/저자/저널 조회
        URI summaryUri = UriComponentsBuilder.fromUriString(BASE_URL + "/esummary.fcgi")
                .queryParam("db", "pubmed")
                .queryParam("id", String.join(",", pmids))
                .queryParam("retmode", "json")
                .build().toUri();

        List<PaperSummary> results = new ArrayList<>();
        try {
            String summaryResponse = fetchWithRetry(summaryUri);
            JsonNode resultNode = objectMapper.readTree(summaryResponse).path("result");

            for (String pmid : pmids) {
                JsonNode paper = resultNode.path(pmid);
                if (paper.isMissingNode()) continue;

                List<String> authors = new ArrayList<>();
                for (JsonNode author : paper.path("authors")) {
                    authors.add(author.path("name").asText());
                }

                List<String> pubTypes = new ArrayList<>();
                for (JsonNode t : paper.path("pubtype")) {
                    String s = t.asText();
                    if (s != null && !s.isBlank()) pubTypes.add(s);
                }

                boolean hasPmc = false;
                for (JsonNode aid : paper.path("articleids")) {
                    if ("pmc".equalsIgnoreCase(aid.path("idtype").asText())
                            && !aid.path("value").asText("").isBlank()) {
                        hasPmc = true;
                        break;
                    }
                }

                results.add(PaperSummary.builder()
                        .pmid(pmid)
                        .title(paper.path("title").asText())
                        .authors(authors)
                        .pubDate(paper.path("pubdate").asText())
                        .epubDate(paper.path("epubdate").asText())
                        .journal(paper.path("source").asText())
                        .pubTypes(pubTypes)
                        .hasPmc(hasPmc)
                        .build());
            }
        } catch (Exception e) {
            log.warn("PubMed esummary 실패: {}", e.getMessage());
        }

        return SearchResponse.builder()
                .papers(results)
                .total(totalCount)
                .page(page)
                .size(size)
                .tooBroad(totalCount > TOO_BROAD_THRESHOLD)
                .correctedQuery(correctedQuery)
                .build();
    }

    public PaperDetail getDetail(String pmid) {
        // 1. esummary: 메타데이터 + PMC ID
        URI summaryUri = UriComponentsBuilder.fromUriString(BASE_URL + "/esummary.fcgi")
                .queryParam("db", "pubmed")
                .queryParam("id", pmid)
                .queryParam("retmode", "json")
                .build().toUri();

        PaperSummary summary;
        String pmcid = null;
        try {
            String summaryResponse = pubmedRestClient.get().uri(summaryUri).retrieve().body(String.class);
            JsonNode paper = objectMapper.readTree(summaryResponse).path("result").path(pmid);

            List<String> authors = new ArrayList<>();
            for (JsonNode author : paper.path("authors")) {
                authors.add(author.path("name").asText());
            }

            for (JsonNode aid : paper.path("articleids")) {
                if ("pmc".equalsIgnoreCase(aid.path("idtype").asText())) {
                    String value = aid.path("value").asText("");
                    if (!value.isBlank()) {
                        pmcid = value;
                        break;
                    }
                }
            }

            summary = PaperSummary.builder()
                    .pmid(pmid)
                    .title(paper.path("title").asText())
                    .authors(authors)
                    .pubDate(paper.path("pubdate").asText())
                    .journal(paper.path("source").asText())
                    .build();
        } catch (Exception e) {
            log.error("PubMed detail 메타데이터 조회 오류 pmid={}", pmid, e);
            return null;
        }

        // 2. efetch: 초록 텍스트
        URI abstractUri = UriComponentsBuilder.fromUriString(BASE_URL + "/efetch.fcgi")
                .queryParam("db", "pubmed")
                .queryParam("id", pmid)
                .queryParam("retmode", "text")
                .queryParam("rettype", "abstract")
                .build().toUri();

        String abstractText = "";
        try {
            abstractText = pubmedRestClient.get().uri(abstractUri).retrieve().body(String.class);
        } catch (Exception e) {
            log.warn("PubMed 초록 조회 오류 pmid={}", pmid, e);
        }

        return PaperDetail.builder()
                .pmid(pmid)
                .pmcid(pmcid)
                .title(summary.getTitle())
                .authors(summary.getAuthors())
                .pubDate(summary.getPubDate())
                .journal(summary.getJournal())
                .abstractText(abstractText)
                .build();
    }

    /**
     * PMC(오픈액세스 PubMed Central)에서 본문 XML을 가져와 텍스트로 추출.
     * 본문이 없거나 임베고된 경우 빈 문자열을 반환한다.
     */
    public String fetchPmcFullText(String pmcid) {
        if (pmcid == null || pmcid.isBlank()) return "";

        URI fullTextUri = UriComponentsBuilder.fromUriString(BASE_URL + "/efetch.fcgi")
                .queryParam("db", "pmc")
                .queryParam("id", pmcid)
                .queryParam("rettype", "xml")
                .build().toUri();

        try {
            long start = System.currentTimeMillis();
            String xml = fetchWithRetry(fullTextUri);
            if (xml == null || xml.isBlank()) {
                log.warn("PMC 본문 응답이 비어 있음 pmcid={}", pmcid);
                return "";
            }
            String text = extractJatsBodyText(xml);
            if (text.length() > MAX_FULL_TEXT_CHARS) {
                text = text.substring(0, MAX_FULL_TEXT_CHARS);
            }
            log.info("PMC 본문 조회 완료 pmcid={} → {}자 ({}ms)",
                    pmcid, text.length(), System.currentTimeMillis() - start);
            return text;
        } catch (Exception e) {
            log.warn("PMC 본문 조회 실패 pmcid={}: {}", pmcid, e.getMessage());
            return "";
        }
    }

    /**
     * JATS XML에서 &lt;body&gt; 영역의 텍스트를 추출. XXE 방지를 위해 외부 엔티티를 비활성화한다.
     */
    private String extractJatsBodyText(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        factory.setNamespaceAware(false);

        DocumentBuilder builder = factory.newDocumentBuilder();
        Document doc = builder.parse(new InputSource(new StringReader(xml)));

        NodeList bodies = doc.getElementsByTagName("body");
        if (bodies.getLength() == 0) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < bodies.getLength(); i++) {
            appendBlockText(bodies.item(i), sb);
        }
        return sb.toString().replaceAll("\\n{3,}", "\n\n").trim();
    }

    private void appendBlockText(Node node, StringBuilder sb) {
        short type = node.getNodeType();
        if (type == Node.TEXT_NODE || type == Node.CDATA_SECTION_NODE) {
            sb.append(node.getNodeValue());
            return;
        }
        if (type != Node.ELEMENT_NODE) {
            return;
        }
        String name = node.getNodeName();
        if ("xref".equals(name) || "fn".equals(name) || "table".equals(name)) {
            return;
        }
        NodeList children = node.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            appendBlockText(children.item(i), sb);
        }
        if (BLOCK_ELEMENTS.contains(name)) {
            sb.append("\n\n");
        }
    }
}
