package com.hyunchang.bioagent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyunchang.bioagent.dto.PaperDetail;
import com.hyunchang.bioagent.dto.PaperSummary;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
public class PubMedService {

    private static final String BASE_URL = "https://eutils.ncbi.nlm.nih.gov/entrez/eutils";

    private final RestClient restClient = RestClient.create();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public List<PaperSummary> search(String query, int maxResults) {
        // 1. esearch: PMID 목록 조회
        URI searchUri = UriComponentsBuilder.fromUriString(BASE_URL + "/esearch.fcgi")
                .queryParam("db", "pubmed")
                .queryParam("term", query)
                .queryParam("retmax", maxResults)
                .queryParam("retmode", "json")
                .build().toUri();

        List<String> pmids = new ArrayList<>();
        try {
            String searchResponse = restClient.get().uri(searchUri).retrieve().body(String.class);
            JsonNode root = objectMapper.readTree(searchResponse);
            for (JsonNode id : root.path("esearchresult").path("idlist")) {
                pmids.add(id.asText());
            }
        } catch (Exception e) {
            log.error("PubMed esearch 오류", e);
            return List.of();
        }

        if (pmids.isEmpty()) return List.of();

        // 2. esummary: 제목/저자/저널 조회
        URI summaryUri = UriComponentsBuilder.fromUriString(BASE_URL + "/esummary.fcgi")
                .queryParam("db", "pubmed")
                .queryParam("id", String.join(",", pmids))
                .queryParam("retmode", "json")
                .build().toUri();

        List<PaperSummary> results = new ArrayList<>();
        try {
            String summaryResponse = restClient.get().uri(summaryUri).retrieve().body(String.class);
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
            log.error("PubMed esummary 오류", e);
        }

        return results;
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
            String summaryResponse = restClient.get().uri(summaryUri).retrieve().body(String.class);
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
            abstractText = restClient.get().uri(abstractUri).retrieve().body(String.class);
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
