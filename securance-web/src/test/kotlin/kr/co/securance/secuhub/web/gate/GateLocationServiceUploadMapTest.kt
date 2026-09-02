package kr.co.securance.secuhub.web.gate

import kr.co.securance.secuhub.TestJpaApplication
import kr.co.securance.secuhub.domain.entity.GateLocation
import kr.co.securance.secuhub.domain.repository.GateLocationRepository
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.transaction.TestTransaction
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Path
import javax.imageio.ImageIO

/**
 * [GateLocationService.uploadMap]의 기존 배치도 파일 삭제 타이밍 회귀 테스트(Opus 리뷰 지적,
 * 2026-09-02).
 *
 * 이 파일시스템 삭제는 DB 트랜잭션과 원자적으로 묶이지 않는 부수효과다 — 엔티티 필드 갱신
 * "전에" 즉시 지우면, 이후(같은 트랜잭션 안에서 이 메서드 뒤에 붙는 로직 실패 등 어떤 이유로든)
 * 트랜잭션이 롤백될 때 DB는 옛 파일명을 계속 가리키는데 실제 파일은 이미 사라진 "유령 참조"
 * 상태가 된다 — 배치도를 다시 열어도 이미지가 표시되지 않는, 이번에 조사한 사용자 증상과 정확히
 * 같은 결과를 만든다. `uploadMap`은 옛 파일 삭제를 `TransactionSynchronization.afterCommit()`으로
 * 미뤄 이를 막는다 — 커밋 전에는 옛 파일이 그대로 남아 있어야 하고, 커밋된 뒤에만 지워져야 한다.
 *
 * `@DataJpaTest`는 기본적으로 각 테스트를 트랜잭션으로 감싸고 끝나면 롤백하므로(그래서
 * `afterCommit()`이 자연히 발동하지 않는다), [TestTransaction]으로 커밋 시점을 직접 제어해
 * "커밋 전에는 파일이 남아 있다 → 커밋하면 지워진다"는 순서 자체를 검증한다.
 */
@DataJpaTest
@ContextConfiguration(classes = [TestJpaApplication::class])
@TestPropertySource(
    properties = [
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=none",
    ],
)
class GateLocationServiceUploadMapTest {

    @Autowired
    private lateinit var locationRepository: GateLocationRepository

    @TempDir
    private lateinit var imageDir: Path

    private lateinit var service: GateLocationService

    @BeforeEach
    fun setUp() {
        service = GateLocationService(locationRepository, imageDir.toString())
    }

    private fun pngBytes(): ByteArray {
        val image = BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB)
        return ByteArrayOutputStream().use { out ->
            ImageIO.write(image, "png", out)
            out.toByteArray()
        }
    }

    @Test
    fun `업로드 성공 후 커밋되기 전까지는 기존 배치도 파일이 남아 있다가 커밋되면 삭제된다`() {
        // 기존에 업로드돼 있던 배치도 파일을 물리적으로 재현.
        val oldFile = imageDir.resolve("old.png").toFile()
        oldFile.writeBytes(pngBytes())
        val location = locationRepository.saveAndFlush(
            GateLocation(locName = "테스트위치", locMap = "old.png", locMapWidth = 10, locMapHeight = 10),
        )

        val newFile = MockMultipartFile("file", "new.png", "image/png", pngBytes())
        service.uploadMap(location.locId!!, newFile)

        // 커밋 전 — DB 엔티티는 이미 새 파일명을 가리키지만, 옛 파일은 아직 지워지지 않아야 한다
        // (그래야 이 시점에서 무슨 이유로든 롤백돼도 DB와 파일 상태가 계속 일치한다).
        assertTrue(oldFile.exists(), "커밋 전에는 옛 배치도 파일이 삭제되면 안 된다")

        TestTransaction.flagForCommit()
        TestTransaction.end()

        // 커밋 후 — 더 이상 참조되지 않는 옛 파일이 실제로 정리돼야 한다.
        assertFalse(oldFile.exists(), "커밋 후에는 옛 배치도 파일이 삭제돼야 한다")
    }
}
