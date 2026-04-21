package com.hyunchang.bioagent.service;

import com.hyunchang.bioagent.dto.GelRecordDto;
import com.hyunchang.bioagent.entity.GelRecord;
import com.hyunchang.bioagent.repository.GelRecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class GelDataService {

    private final GelRecordRepository gelRecordRepository;

    public List<GelRecordDto> findAll() {
        return gelRecordRepository.findAllByOrderByCreatedAtDesc()
                .stream().map(this::toDto).toList();
    }

    public void deleteById(Long id) {
        gelRecordRepository.deleteById(id);
    }

    public GelRecordDto save(GelRecord record) {
        return toDto(gelRecordRepository.save(record));
    }

    public boolean existsByFileHashAndLaneIndex(String fileHash, int laneIndex) {
        return gelRecordRepository.existsByFileHashAndLaneIndex(fileHash, laneIndex);
    }

    public boolean existsByFileName(String fileName) {
        return gelRecordRepository.existsByFileName(fileName);
    }

    public List<GelRecord> findAllWithCtValues() {
        return gelRecordRepository.findAllWithCtValues();
    }

    public List<GelRecord> findAllEntities() {
        return gelRecordRepository.findAll();
    }

    public List<GelRecord> findSimilarByCt(double target, double lower, double upper) {
        return gelRecordRepository.findSimilarByCt(target, lower, upper);
    }

    public String computeSha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(bytes);
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException("해시 계산 실패", e);
        }
    }

    public Double computeLog10Concentration(String label) {
        if (label == null || label.equals("M") || label.equals("NTC")) return null;
        if (label.startsWith("10^")) {
            try {
                return Double.parseDouble(label.substring(3));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    public GelRecordDto toDto(GelRecord r) {
        GelRecordDto dto = new GelRecordDto();
        dto.setId(r.getId());
        dto.setFileName(r.getFileName());
        dto.setLaneIndex(r.getLaneIndex());
        dto.setConcentrationLabel(r.getConcentrationLabel());
        dto.setLog10Concentration(r.getLog10Concentration());
        dto.setCtValue(r.getCtValue());
        dto.setBandIntensity(r.getBandIntensity());
        dto.setBandArea(r.getBandArea());
        dto.setRelativeIntensity(r.getRelativeIntensity());
        dto.setBandWidth(r.getBandWidth());
        dto.setBandHeight(r.getBandHeight());
        dto.setIsSaturated(r.getIsSaturated());
        dto.setIsNegative(r.getIsNegative());
        dto.setWarning(r.getWarning());
        dto.setCreatedAt(r.getCreatedAt());
        return dto;
    }
}
