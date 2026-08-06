package kr.co.securance.secuhub.server.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * `securance.location.*` 설정 — 레거시 ini `[LOCATION]` 섹션(`PLACE_CD`/`GROUP_CD`) 대응.
 *
 * [스코프] 설정 스캐폴드만 추가한다. 레거시에서도 이 값들의 실사용처가 불분명했으므로, 실제
 * 사용 로직(어느 서비스가 이 값을 읽어 무엇에 쓰는지)은 과도한 억측을 피하기 위해 구현하지 않는다
 * — 후속 작업에서 실사용처가 확인되면 그때 연동한다.
 */
@ConfigurationProperties(prefix = "securance.location")
data class LocationProperties(
    /** 레거시 [LOCATION] PLACE_CD — 설치 장소 코드로 추정된다. */
    val placeCd: String = "",

    /** 레거시 [LOCATION] GROUP_CD — 설치 그룹 코드로 추정된다. */
    val groupCd: String = "",
)

/**
 * `securance.cloud.*` 설정 — 레거시 ini `[CLOUD]` 섹션(`CLOUD_ADDR`/`CLOUD_PORT`) 대응.
 *
 * [스코프] [LocationProperties]와 동일하게 설정 스캐폴드만 추가한다. 레거시 `ClsCommon.GetCloudDBServer`/
 * `GetCloudDBPort`는 존재했으나 호출부 확인 없이 "클라우드 DB 연동" 로직을 새로 만드는 것은 이번
 * 작업 범위를 벗어난다 — 실사용처는 후속 작업으로 남긴다.
 */
@ConfigurationProperties(prefix = "securance.cloud")
data class CloudProperties(
    /** 레거시 [CLOUD] CLOUD_ADDR. */
    val addr: String = "",

    /** 레거시 [CLOUD] CLOUD_PORT. */
    val port: Int = 0,
)
