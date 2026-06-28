// 本地构建走阿里云镜像加速；CI 环境（GitHub Actions 自动设置 CI=true）走官方仓库。
val useAliyunMirror = System.getenv("CI") == null

pluginManagement {
    repositories {
        if (useAliyunMirror) {
            // 国内优先：阿里云镜像（Gradle 插件 + Maven Central + Google 三件套）
            maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
            maven { url = uri("https://maven.aliyun.com/repository/public") }
            maven { url = uri("https://maven.aliyun.com/repository/google") }
        }
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        if (useAliyunMirror) {
            // 国内优先：阿里云镜像
            maven { url = uri("https://maven.aliyun.com/repository/public") }
            maven { url = uri("https://maven.aliyun.com/repository/google") }
            maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        }
        google()
        mavenCentral()
    }
}

rootProject.name = "SurveyController"
include(":app")
