# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Notable is an Android note-taking app designed for BOOX e-ink devices, built with Kotlin and Jetpack Compose. The app supports handwriting recognition, notebook organization, and includes AI integration features with locally hosted LLMs.

## Development Commands

**Build and Test:**
- `./gradlew assembleDebug` - Build debug APK
- `./gradlew assembleRelease` - Build release APK  
- `./gradlew test` - Run unit tests
- `./gradlew connectedAndroidTest` - Run instrumented tests
- `./gradlew clean` - Clean build artifacts

**Development:**
- Open project in Android Studio (preferred IDE)
- Target SDK: 33, Min SDK: 29
- Use Android Studio's built-in linting and formatting

## Architecture

**Package Structure:**
- `views/` - Main screen composables (HomeView, EditorView, PagesView, Router)
- `components/` - Reusable UI components (Toolbar, PagePreview, etc.)
- `classes/` - Core business logic (DrawCanvas, EditorControlTower, AppRepository, Auth/API clients)
- `db/` - Room database entities and repositories (Page, Notebook, Folder, Stroke, ChatMessage)
- `utils/` - Helper functions for drawing, page management, history
- `ui/theme/` - Compose theme configuration
- `modals/` - Configuration data classes

**Key Architecture Patterns:**
- MVVM with Repository pattern for data access
- Room database for local storage with schema versioning (schemas/ directory)
- Jetpack Compose for UI with navigation-compose
- Coroutines and StateFlow for async operations
- Dependency injection through constructor parameters

**Core Classes:**
- `MainActivity.kt` - Entry point, handles e-ink device integration
- `Router.kt` - Navigation between library, editor, and pages views
- `DrawCanvas.kt` - Custom drawing surface for handwriting input
- `EditorControlTower.kt` - Centralized editor state management
- `AppRepository.kt` - Main data repository coordinating all database access

**E-ink Device Integration:**
- Uses Onyx SDK for pen input and display optimization
- `SCREEN_WIDTH`/`SCREEN_HEIGHT` globals for device dimensions
- Special gesture handling for e-ink devices (double-tap, swipe gestures)

**API Integration:**
- Migrated from AWS to Convex backend
- `ConvexApiClient.kt` and `ConvexApiService.kt` for API communication
- `AuthManager.kt` for authentication with secure token storage
- `ConvexChatService.kt` for AI chat integration

## Development Notes

**Database:**
- Room database with migration support - schemas stored in `app/schemas/`
- When adding new entities or modifying existing ones, create migration scripts
- Database version currently at 30+ iterations

**Drawing System:**
- Custom drawing implementation optimized for e-ink displays
- Stroke data stored as paths with pressure sensitivity
- Undo/redo system with history management in `utils/history.kt`

**Build Configuration:**
- Uses KSP (Kotlin Symbol Processing) for Room compilation
- Proguard disabled for easier debugging
- Compose version managed centrally in root build.gradle

**Testing:**
- JUnit 4 for unit tests
- Espresso for UI testing
- Use `testInstrumentationRunner` for device-specific testing