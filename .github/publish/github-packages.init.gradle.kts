// publish.yml 전용. 배포할 소스의 core 빌드 스크립트에 GitHubPackages 저장소가 없으면(이 workflow 이전 태그) 같은 저장소를 더한다.
// 저장소 정의는 core/build.gradle.kts 가 맞고, 이 파일은 그 정의가 없는 옛 태그만 메운다. url 을 바꾸면 두 곳을 함께 고친다.
import org.gradle.api.publish.PublishingExtension

allprojects {
    pluginManager.withPlugin("maven-publish") {
        afterEvaluate {
            val repositories = extensions.getByType(PublishingExtension::class.java).repositories
            if (repositories.findByName("GitHubPackages") == null) {
                repositories.maven {
                    name = "GitHubPackages"
                    url = uri("https://maven.pkg.github.com/Nucode01/nucode-ble-core-android")
                    credentials {
                        username = System.getenv("GITHUB_ACTOR")
                        password = System.getenv("GITHUB_TOKEN")
                    }
                }
            }
        }
    }
}
