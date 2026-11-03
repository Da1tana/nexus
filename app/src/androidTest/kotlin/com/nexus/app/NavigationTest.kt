package com.nexus.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NavigationTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun bottomNavigation_opensEverySection() {
        compose.onAllNodesWithText("Сообщения").onFirst().assertIsDisplayed()
        compose.onNodeWithText("Диалоги").assertIsDisplayed()
        compose.onNodeWithText("Лента").performClick()

        compose.onNodeWithText("Поиск").performClick()
        compose.onNodeWithText("Текст сообщения").assertIsDisplayed()

        compose.onNodeWithText("Контакты").performClick()
        compose.onNodeWithText("Поиск контакта").assertIsDisplayed()

        compose.onNodeWithText("Настройки").performClick()
        compose.onNodeWithText("Хранение истории").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun settings_opensAppsAndDiagnostics_andBack() {
        compose.onNodeWithText("Настройки").performClick()
        compose.onNodeWithText("Приложения").performClick()
        compose.onNodeWithText("Найти приложение").assertIsDisplayed()
        compose.onNodeWithContentDescription("Назад").performClick()

        compose.onNodeWithText("Диагностика").performScrollTo().performClick()
        compose.onNodeWithText("Сбор сообщений").assertIsDisplayed()
        compose.onNodeWithContentDescription("Назад").performClick()
        compose.onNodeWithText("Хранение истории").performScrollTo().assertIsDisplayed()
    }
}
