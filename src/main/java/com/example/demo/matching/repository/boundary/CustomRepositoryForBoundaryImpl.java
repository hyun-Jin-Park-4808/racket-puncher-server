package com.example.demo.matching.repository.boundary;

import com.example.demo.entity.Matching;
import com.example.demo.matching.dto.LocationDto;
import com.example.demo.matching.repository.BaseCustomRepository;
import com.example.demo.type.RecruitStatus;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.core.types.dsl.Expressions;
import com.querydsl.core.types.dsl.StringTemplate;
import com.querydsl.jpa.JPQLQuery;
import com.querydsl.jpa.impl.JPAQueryFactory;
import jakarta.persistence.EntityManager;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;

import static com.example.demo.entity.QMatching.matching;

@Repository
public class CustomRepositoryForBoundaryImpl extends BaseCustomRepository implements CustomRepositoryForBoundary {
    public CustomRepositoryForBoundaryImpl(JPAQueryFactory queryFactory, EntityManager entityManager) {
        super(queryFactory, entityManager);
    }

    @Override
    public PageImpl<Matching> findAllWithinBoundary(LocationDto center, LocationDto northEastBound, LocationDto southWestBound, Pageable pageable) {
        BooleanExpression withinBoundary = within(southWestBound.getLat(), northEastBound.getLat(), southWestBound.getLon(), northEastBound.getLon());
        StringTemplate distanceTemplate = Expressions.stringTemplate(HAVERSINE_FORMULA, matching.lon, matching.lat, center.getLon(), center.getLat());

        JPQLQuery<Matching> matchingQuery = queryFactory
                .selectFrom(matching)
                .where(withinBoundary)
                .orderBy(distanceTemplate.asc());

        return getPageImpl(pageable, matchingQuery, Matching.class);
    }

    private BooleanExpression within(Double latLowerBound, Double latUpperBound, Double lonLeftBound, Double lonRightBound) {
        return matching.lat.between(latLowerBound, latUpperBound)
                .and(matching.lon.between(lonLeftBound, lonRightBound))
                .and(matching.recruitStatus.eq(RecruitStatus.OPEN))
                .and(matching.recruitDueDateTime.after(LocalDateTime.now()));
    }
}
