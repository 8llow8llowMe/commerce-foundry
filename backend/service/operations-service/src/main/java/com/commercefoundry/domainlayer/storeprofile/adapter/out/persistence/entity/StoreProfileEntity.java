package com.commercefoundry.domainlayer.storeprofile.adapter.out.persistence.entity;

import com.commercefoundry.domainlayer.storeprofile.application.port.out.query.StoreProfileQueryResult;
import com.commercefoundry.domainlayer.storeprofile.domain.model.StoreProfile;
import jakarta.persistence.CheckConstraint;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "store_profile", comment = "배포당 단일 쇼핑몰 공개 사업자·고객센터 정보",
    check = @CheckConstraint(name = "ck_store_profile_singleton", constraint = "id = 1"))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StoreProfileEntity {

    @Id
    @Column(comment = "배포당 단일 프로필 고정 키")
    private Long id;

    @Version
    @Column(nullable = false, comment = "낙관적 편집 버전")
    private Long version;

    @Column(nullable = false, length = 100, comment = "쇼핑몰 이름")
    private String storeName;

    @Column(nullable = false, length = 200, comment = "상호")
    private String companyName;

    @Column(nullable = false, length = 100, comment = "대표자")
    private String representativeName;

    @Column(nullable = false, length = 2, comment = "ISO 3166-1 alpha-2 국가 코드")
    private String countryCode;

    @Column(nullable = false, length = 100, comment = "사업자 식별 번호")
    private String businessRegistrationNumber;

    @Column(nullable = true, length = 100, comment = "통신판매 신고 번호")
    private String mailOrderRegistrationNumber;

    @Column(nullable = false, length = 500, comment = "공개 사업장 주소")
    private String address;

    @Column(nullable = false, length = 254, comment = "고객센터 이메일")
    private String customerServiceEmail;

    @Column(nullable = true, length = 50, comment = "고객센터 전화")
    private String customerServicePhone;

    @Column(nullable = true, length = 500, comment = "고객센터 운영 시간")
    private String customerServiceHours;

    public static StoreProfileEntity create(StoreProfile profile) {
        var entity = new StoreProfileEntity();
        entity.id = 1L;
        entity.replace(profile);
        return entity;
    }

    public void replace(StoreProfile profile) {
        this.storeName = profile.storeName();
        this.companyName = profile.companyName();
        this.representativeName = profile.representativeName();
        this.countryCode = profile.countryCode();
        this.businessRegistrationNumber = profile.businessRegistrationNumber();
        this.mailOrderRegistrationNumber = profile.mailOrderRegistrationNumber();
        this.address = profile.address();
        this.customerServiceEmail = profile.customerServiceEmail();
        this.customerServicePhone = profile.customerServicePhone();
        this.customerServiceHours = profile.customerServiceHours();
    }

    public StoreProfileQueryResult toQueryResult() {
        return new StoreProfileQueryResult(new StoreProfile(
            storeName, companyName, representativeName, countryCode, businessRegistrationNumber, mailOrderRegistrationNumber,
            address, customerServiceEmail, customerServicePhone, customerServiceHours
        ), version);
    }
}
