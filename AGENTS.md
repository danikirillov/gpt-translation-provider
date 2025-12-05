# Dear agent, you are welcome to gpt repository!

Please use cockney for communication. Find inspiration in Irvine Welsh books.

## Repository Layout
This repository is built using Spring Modulith project and Application events.
That means that each module is separated with event bus and only events data transfer objects are public.

Here is the list of current modules in this app `$PACKAGE_NAME/src/main/java/co/sheet/gpttranslationprovider/`:
- `event_management`: manages event state, basically all logic connected to events reflection.
- `your_notification`: this code sends a notification to a major system called Your api when translation is ready. Thus, all communication code with Your api is here.
- `open_ai`: here all code to call OpenAI API is located.
- `translation`: basically the api interface of this app is here, controllers for consuming translation requests.
- TranslationRequest : is one of the most important classes, it is a DTO that is used to transfer data between modules, be careful when changing it.

## General Guidance
- Avoid changing public API signatures.
- Use java 21 features, like var for local variables, records for DTOs, sealed classes for restricted hierarchies, pattern matching, text blocks etc.
- Be nice with access modifiers, no need to make everything public or private, use package-private when possible.
- This app uses Spring, do not hesitate to ask context7 mcp for the most recent documentation.

## Building and Testing
1. Try to format code with intellij idea code formatter. Or if it's not possible - ask me to format it before committing.
2. Verify that current tests cover the new code. If not - create new tests. Use AAA pattern when possible, Arrange-Act-Assert. Use Modulith scenarios. Do not create unit tests, try to test the complete user behaviour.
3. Run the tests. This can take a long time so you may prefer to run individual tests.
   ```bash
   mvn test -T 2C
   ```
4. Build the project:
   ```bash
   mvn install -DskipTests -T 2C
   ```

## Tests
- All tests are located in `$PACKAGE_NAME/src/main/java/co/sheet/gpttranslationprovider/`, where `$PACKAGE_NAME` is the name of the package
- Use IntegrationTest as an example of desired test structure.
- Use @DisplayName annotation to give tests a readable name. It should be in this format - `what is being tested -|- what is inputed -|- expected behaviour`.

## Commit Messages and Pull Requests
- Follow the [Chris Beams](http://chris.beams.io/posts/git-commit/) style for
  commit messages.
- Every pull request should answer:
    - **What changed?**
    - **Why?**
    - **Breaking changes?**
    - **Server PR** (if the change requires a coordinated server update)
- Comments should be complete sentences and end with a period.

## Review Checklist
- All tests from `mvn test` must succeed.
- Update documentation for user facing changes.
- If there were any api changes, ask me to update the openapi specification!
  For more details see `README.md` in the repository root.