package com.example.demo.matching.service;

import com.example.demo.apply.dto.ApplyDto;
import com.example.demo.apply.repository.ApplyRepository;
import com.example.demo.common.FindEntity;
import com.example.demo.entity.Apply;
import com.example.demo.entity.Matching;
import com.example.demo.entity.SiteUser;
import com.example.demo.exception.RacketPuncherException;
import com.example.demo.matching.dto.*;
import com.example.demo.matching.repository.MatchingRepository;
import com.example.demo.notification.service.NotificationService;
import com.example.demo.openfeign.feignclient.LatAndLonApiFeignClient;
import com.example.demo.openfeign.service.weather.WeatherService;
import com.example.demo.siteuser.repository.SiteUserRepository;
import com.example.demo.type.ApplyStatus;
import com.example.demo.type.NotificationType;
import com.example.demo.type.PenaltyType;
import com.example.demo.type.PrecipitationType;
import com.example.demo.type.RecruitStatus;
import com.example.demo.util.geometry.GeometryUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static com.example.demo.exception.type.ErrorCode.*;

@Service
@RequiredArgsConstructor
@Transactional
public class MatchingServiceImpl implements MatchingService {

    private final MatchingRepository matchingRepository;
    private final ApplyRepository applyRepository;
    private final FindEntity findEntity;
    private final SiteUserRepository siteUserRepository;
    private final NotificationService notificationService;
    private final LatAndLonApiFeignClient latAndLonApiFeignClient;
    private final WeatherService weatherService;
    private static final DateTimeFormatter formForDate = DateTimeFormatter.ofPattern("yyyy-MM-dd");


    @Value("${kakao.client_id}")
    private String apiKey;

    private static boolean isOrganizer(long userId, Matching matching) {
        return matching.getSiteUser().getId() == userId;
    }

    @Override
    public Matching create(String email, MatchingDetailRequestDto matchingDetailRequestDto) {
        SiteUser siteUser = siteUserRepository.findByEmail(email)
                .orElseThrow(() -> new RacketPuncherException(EMAIL_NOT_FOUND));
        List<Double> latAndLon = getLatAndLon(matchingDetailRequestDto.getLocation());
        matchingDetailRequestDto.setLat(latAndLon.get(0));
        matchingDetailRequestDto.setLon(latAndLon.get(1));
        Matching matching = matchingRepository.save(Matching.fromDto(matchingDetailRequestDto, siteUser));
        saveApplyForOrganizer(matching, siteUser);
        if (!LocalDate.now().format(formForDate).equals(matchingDetailRequestDto.getDate())) {
            return matching;
        }
        var weatherDto = weatherService.getWeatherResponseDtoByMatching(matching);

        if (!PrecipitationType.NICE.equals(weatherDto.getPrecipitationType())) {
            matching.changeRecruitStatus(RecruitStatus.WEATHER_ISSUE);
            notificationService.createAndSendNotification(siteUser, matching,
                    NotificationType.makeWeatherIssueMessage(weatherDto));
            return matching;
        }
        notificationService.createAndSendNotification(siteUser, matching,
                NotificationType.makeWeatherMessage());
        return matching;
    }

    private void saveApplyForOrganizer(Matching matching, SiteUser siteUser) {
        var applyDto = ApplyDto.builder()
                .matching(matching)
                .siteUser(siteUser)
                .build();
        Apply apply = applyRepository.save(Apply.fromDto(applyDto));
        apply.changeApplyStatus(ApplyStatus.ACCEPTED);
    }

    @Override
    @Transactional
    public Matching update(String email, Long matchingId, MatchingDetailRequestDto matchingDetailRequestDto) {
        var siteUser = siteUserRepository.findByEmail(email)
                .orElseThrow(() -> new RacketPuncherException(USER_NOT_FOUND));

        var matching = findEntity.findMatching(matchingId);

        var acceptedApplies
                = applyRepository.findAllByMatching_IdAndApplyStatus(matchingId, ApplyStatus.ACCEPTED);

        validateOrganizer(matchingId, siteUser);
        updateLatAndLon(matchingDetailRequestDto, matching);
        sendNotificationToApplyUser(matchingId, siteUser, matching, NotificationType.MODIFY_MATCHING);
        penalizeToOrganizer(acceptedApplies, siteUser, PenaltyType.MATCHING_MODIFY);
        acceptedApplies.forEach(apply -> {
            if (!apply.getSiteUser().equals(siteUser)) {
                apply.changeApplyStatus(ApplyStatus.PENDING);
            }
        });

        matching.update(Matching.fromDto(matchingDetailRequestDto, siteUser));
        return matching;
    }

    private void updateLatAndLon(MatchingDetailRequestDto matchingDetailRequestDto, Matching matching) {
        if(!matchingDetailRequestDto.getLocation().equals(matching.getLocation())){
            List<Double> latAndLon = getLatAndLon(matchingDetailRequestDto.getLocation());
            matchingDetailRequestDto.setLat(latAndLon.get(0));
            matchingDetailRequestDto.setLon(latAndLon.get(1));
        }
    }

    private void penalizeToOrganizer(List<Apply> confirmedApplies, SiteUser siteUser, PenaltyType penaltyType) {
        if (confirmedApplies.size() >= 2) {
            siteUser.penalize(penaltyType);
        }
    }

    private void sendNotificationToApplyUser(Long matchingId, SiteUser siteUser, Matching matching,
                                             NotificationType notificationType) {
        var applies = applyRepository.findAllByMatching_Id(matchingId);

        applies.forEach(apply -> {
            if (!apply.getSiteUser().equals(siteUser)) {
                notificationService.createAndSendNotification(apply.getSiteUser(), matching, notificationType);
            }
        });
    }

    @Override
    public void delete(String email, Long matchingId) {
        SiteUser siteUser = siteUserRepository.findByEmail(email)
                .orElseThrow(() -> new RacketPuncherException(USER_NOT_FOUND));

        Matching matching = findEntity.findMatching(matchingId);

        var acceptedApplies
                = applyRepository.findAllByMatching_IdAndApplyStatus(matchingId, ApplyStatus.ACCEPTED);

        validateOrganizer(matchingId, siteUser);

        if (!matching.getRecruitStatus().equals(RecruitStatus.WEATHER_ISSUE)) { // 우천으로 인한 취소가 아니면 패널티 적용
            penalizeToOrganizer(acceptedApplies, siteUser, PenaltyType.MATCHING_DELETE);
        }

        sendNotificationToApplyUser(matchingId, siteUser, matching, NotificationType.DELETE_MATCHING);
        applyRepository.deleteAll(acceptedApplies);

        matchingRepository.delete(matching);
    }

    private void validateOrganizer(Long matchingId, SiteUser siteUser) {
        if (!isUserMadeThisMatching(matchingId, siteUser)) {
            throw new RacketPuncherException(PERMISSION_DENIED_TO_EDIT_AND_DELETE_MATCHING);
        }
    }

    @Override
    public Page<MatchingPreviewDto> getMatchingByFilter(FilterDto filterDto, Pageable pageable) {
        if (FilterDto.isFilterDtoEmpty(filterDto)) {
            return matchingRepository.findByRecruitStatusAndRecruitDueDateTimeAfter(RecruitStatus.OPEN, LocalDateTime.now(), pageable)
                    .map(MatchingPreviewDto::fromEntity);
        }

        return matchingRepository.findAllByFilter(filterDto, pageable)
                .map(MatchingPreviewDto::fromEntity);
    }

    @Override
    public Page<MatchingPreviewDto> getMatchingWithinDistance(LocationDto locationDto, Double distance, Pageable pageable) {
        Double x = locationDto.getLat();
        Double y = locationDto.getLon();
        LocationDto northEastBound = GeometryUtil.calculate(x, y, distance / 2, 45.0);
        LocationDto southWestBound = GeometryUtil.calculate(x, y, distance / 2, 225.0);
        return matchingRepository.findAllWithinBoundary(locationDto, northEastBound, southWestBound, pageable)
                .map(MatchingPreviewDto::fromEntity);
    }

    private List<Double> getLatAndLon(String address) {
        LatAndLonResponseDto latAndLonResponse = latAndLonApiFeignClient.getLatAndLon(address, "KakaoAK " + apiKey);
        try {
            DocumentForAddressDto firstDocument = latAndLonResponse.getDocuments().get(0);
            double lon = Double.parseDouble(firstDocument.getX()); // 경도
            double lat = Double.parseDouble(firstDocument.getY()); // 위도
            return new ArrayList<>(Arrays.asList(lat, lon));
        } catch (Exception e) {
            throw new RacketPuncherException(LAT_AND_LON_NOT_FOUND);
        }
    }

    @Override
    public MatchingDetailResponseDto getDetail(Long matchingId) {
        Matching matching = findEntity.findMatching(matchingId);
        return MatchingDetailResponseDto.fromEntity(matching);
    }

    private boolean isUserMadeThisMatching(Long matchingId, SiteUser siteUser) {
        return matchingRepository.existsByIdAndSiteUser(matchingId, siteUser);
    }

    @Override
    public ApplyContents getApplyContents(String email, long matchingId) {
        var siteUser = siteUserRepository.findByEmail(email)
                .orElseThrow(() -> new RacketPuncherException(EMAIL_NOT_FOUND));
        var matching = findEntity.findMatching(matchingId);
        var recruitNum = matching.getRecruitNum();
        var acceptedNum = matching.getAcceptedNum();
        var applyNum = applyRepository.countByMatching_IdAndApplyStatus(matchingId, ApplyStatus.PENDING).orElse(0);
        var appliedMembers = findAppliedMembers(matchingId);
        var acceptedMembers = findAcceptedMembers(matchingId);
        var apply =  applyRepository.findBySiteUser_IdAndMatching_Id(siteUser.getId(), matchingId);
        var isApplied = true;
        if (apply.isEmpty() || ApplyStatus.CANCELED.equals(apply.get().getApplyStatus())) {
            isApplied = false;
        }
        if (isOrganizer(siteUser.getId(), matching)) {
            return ApplyContents.builder()
                    .isApplied(isApplied)
                    .applyNum(applyNum)
                    .recruitNum(recruitNum)
                    .acceptedNum(acceptedNum)
                    .appliedMembers(appliedMembers)
                    .acceptedMembers(acceptedMembers)
                    .build();
        }

        return ApplyContents.builder()
                .isApplied(isApplied)
                .recruitNum(recruitNum)
                .acceptedNum(acceptedNum)
                .acceptedMembers(acceptedMembers)
                .build();
    }

    private List<ApplyMember> findAcceptedMembers(long matchingId) {
        return applyRepository.findAllByMatching_IdAndApplyStatus(matchingId, ApplyStatus.ACCEPTED)
                .stream().map(ApplyMember::from).collect(Collectors.toList());
    }

    private List<ApplyMember> findAppliedMembers(long matchingId) {
        return applyRepository.findAllByMatching_IdAndApplyStatus(matchingId, ApplyStatus.PENDING)
                .stream().map(ApplyMember::from).collect(Collectors.toList());
    }
}