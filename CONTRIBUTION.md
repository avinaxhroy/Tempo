# Contributing to Tempo

**Thank you for your interest in making Tempo better!** 🎉

Your contributions—whether bug fixes, features, documentation, or testing—are genuinely valued. This guide will help you contribute effectively while understanding the project's goals and workflow.

## About the Project

Tempo is a **source-available** project with a clear vision: to be the best local-first music companion app that respects user privacy and data ownership. While I built it to solve a specific problem, community contributions help make it better for everyone.

The codebase is open for learning, auditing, and contribution, with a license that protects against commercial exploitation while enabling collaboration.

## Project Philosophy

Tempo is built on these principles:
- **User-first**: Features serve real user needs, not trends
- **Privacy-first**: Data stays on the device, no tracking
- **Local-first**: No cloud dependency
- **Intentional design**: Every feature has a purpose

**What this means for contributions:**
- Quality and stability matter more than feature count
- Changes should align with the core philosophy
- Thoughtful, focused improvements are preferred over large rewrites
- User experience consistency is important

## How to Contribute

### Contribution Workflow

```
1. Fork the repository
2. Create a feature branch
3. Make your changes
4. Submit a Pull Request
```

**What to include in your PR:**
- Clear explanation of what changed and why
- Screenshots or videos for UI changes
- Test cases for bug fixes (when applicable)
- Reference to related issues (if any)

All PRs are reviewed carefully. I may:
- Merge as-is
- Request changes or improvements
- Ask questions for clarification
- Suggest alternative approaches

**Note:** Not all PRs will be merged—but that's okay! Sometimes it's about timing, fit, or project direction. A declined PR isn't a judgment of your skills.

### Types of Contributions Welcome

**🐛 Bug Fixes**
- Crashes, UI glitches, data issues
- Performance improvements
- Edge case handling

**✨ Feature Enhancements**
- Improvements to existing features
- Better error handling
- Accessibility improvements

**📝 Documentation**
- Code comments
- README improvements
- API documentation
- User guides

**🌍 Localization**
- Translations
- RTL support
- Regional improvements

**🧪 Testing & QA**
- Bug reports with reproduction steps
- Edge case testing
- Performance profiling

### Before Starting Major Work

For significant changes, **please open an issue first** to discuss:
- Large architectural changes
- New major features
- UI redesigns
- Changes to core behavior

This helps avoid wasted effort on changes that may not align with the project's direction.

## UI & Design Contributions

Tempo has an established visual language and interaction model built with Jetpack Compose and Material 3.

**UI improvements welcome:**
- Usability enhancements (better spacing, alignment, hierarchy)
- Accessibility improvements (contrast, screen readers, tap targets)
- Consistency fixes across screens
- Animation polish

**Please discuss first:**
- Complete UI redesigns
- Changes to the visual identity (colors, typography, iconography)
- Major navigation changes
- Replacing existing design patterns

> **Note:** Design work in Figma is appreciated, but implementation in code is what matters. Focus on working prototypes when possible.

## Feature Contributions

**Before building a new feature:**
1. Check existing issues—it might already be planned
2. Open an issue to discuss the idea
3. Describe the **problem** you're solving, not just the solution
4. Wait for feedback before investing significant time

Some feature requests may be deferred, scoped differently, or declined based on:
- Alignment with project philosophy
- Maintenance complexity
- Impact on app size or performance
- User experience consistency

## Decision Making

Tempo is maintained by a single owner with a clear vision for the product. This ensures:
- **Consistency**: Clear direction and cohesive experience
- **Quality**: Thoughtful review of all changes
- **Sustainability**: Manageable scope and maintenance

**This doesn't mean contributions are unwelcome**—it means they're reviewed carefully to ensure they benefit users and align with the project's goals.

If a PR is declined, it's about fit with the project direction, not a judgment of your abilities. Feedback will be provided to help you understand the decision.

## Attribution & Credit

- All contributors are credited in git commit history
- Significant contributions may be highlighted in documentation
- The project remains authored and maintained by its original creator

## Code Quality Guidelines

To maintain a healthy codebase:
- **Follow existing patterns**: Match the code style and architecture already in place
- **Keep dependencies minimal**: Only add libraries when truly necessary
- **Stay focused**: One PR should address one issue or feature
- **Write for humans**: Code should be readable and maintainable
- **Test your changes**: Verify the feature works and doesn't break existing functionality

## Development Environment

- **Language**: Kotlin
- **UI**: Jetpack Compose (Material 3)
- **Architecture**: MVVM + Clean Architecture with Hilt
- **Min SDK**: 26 (Android 8.0)
- **Target SDK**: 34 (Android 14)

Run `./gradlew build` to ensure your changes compile successfully.

## Community Values

**Welcome contributors who:**
- Want to learn and grow
- Care about user privacy and data ownership
- Value quality over quantity
- Respect the project's vision
- Communicate thoughtfully

**This might not be the right fit if you're looking to:**
- Rush changes without review
- Fundamentally alter the project's direction
- Fork for commercial purposes (see LICENSE)
- Ignore maintainer feedback

## Contributor License

By contributing to Tempo, you agree that your contributions will be licensed under the project's [Tempo Source Available License](LICENSE).

Your contributions remain attributed to you in the git history and may be acknowledged in documentation for significant work.

## Getting Help

- **Questions?** Open a discussion or issue
- **Stuck?** Ask for help—collaboration is encouraged
- **Found a bug?** Report it with steps to reproduce
- **Idea for improvement?** Share it in an issue first

## Thank You

Every contribution makes Tempo better—whether it's a one-line typo fix or a major feature. Your time and effort are valued.

Tempo is **source-available** to protect it from commercial exploitation while keeping it open for learning, auditing, and collaboration. This approach ensures the app stays free, privacy-focused, and user-first.

Thank you for being part of this project. 🎵

---

**Ready to contribute?** Fork the repo and submit your first PR!

**Questions?** Open an issue or discussion—I'm happy to help.
