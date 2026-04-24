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

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class PubMedService {

    private static final String BASE_URL = "https://eutils.ncbi.nlm.nih.gov/entrez/eutils";

    private final RestClient pubmedRestClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final int TOO_BROAD_THRESHOLD = 200;

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
        String correctedQuery = checkSpelling(query);
        String searchQuery = correctedQuery != null ? correctedQuery : query;

        int retStart = (page - 1) * size;

        // 1. esearch: 총 건수 확인 및 현재 페이지 PMID 조회
        URI searchUri = UriComponentsBuilder.fromUriString(BASE_URL + "/esearch.fcgi")
                .queryParam("db", "pubmed")
                .queryParam("term", searchQuery)
                .queryParam("retstart", retStart)
                .queryParam("retmax", size)
                .queryParam("retmode", "json")
                .build().toUri();

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

                results.add(PaperSummary.builder()
                        .pmid(pmid)
                        .title(paper.path("title").asText())
                        .authors(authors)
                        .pubDate(paper.path("pubdate").asText())
                        .journal(paper.path("source").asText())
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
        // 1. esummary: 메타데이터
        URI summaryUri = UriComponentsBuilder.fromUriString(BASE_URL + "/esummary.fcgi")
                .queryParam("db", "pubmed")
                .queryParam("id", pmid)
                .queryParam("retmode", "json")
                .build().toUri();

        PaperSummary summary;
        try {
            String summaryResponse = pubmedRestClient.get().uri(summaryUri).retrieve().body(String.class);
            JsonNode paper = objectMapper.readTree(summaryResponse).path("result").path(pmid);

            List<String> authors = new ArrayList<>();
            for (JsonNode author : paper.path("authors")) {
                authors.add(author.path("name").asText());
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
                .title(summary.getTitle())
                .authors(summary.getAuthors())
                .pubDate(summary.getPubDate())
                .journal(summary.getJournal())
                .abstractText(abstractText)
                .build();
    }
}
