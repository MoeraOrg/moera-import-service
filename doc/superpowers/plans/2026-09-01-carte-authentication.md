# Carte Authentication Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace per-destination admin tokens with one process-wide Moera identity that signs a fresh, IP-bound carte
for every Moera API request.

**Architecture:** Bind and validate the importer name, 32-byte hex private signing key, and non-empty address list in a
focused Spring configuration component. A carte-source factory closes over each destination name and signs on every
`CarteSource.getCarte()` call; `MoeraClientFactory` resolves the destination normally but uses peer/carte
authentication. Flyway removes `destinations.credential_ref`, and the administration API and CLI stop accepting it.

**Tech Stack:** Java 25, Spring Boot 4.1, Moera Java library, Maven, Flyway, jOOQ, PostgreSQL/Testcontainers, JUnit 5,
Python standard-library `unittest`.

**Spec:** `doc/architecture/import-service.md`, especially sections 4.2, 17, 24, 32.2, and 45.

## Global Constraints

- Use Maven and Java 25 with Spring; do not introduce reactive code.
- Flyway SQL is the only schema source of truth; jOOQ remains inside the persistence layer.
- Use a new `V007` migration. Never edit already released migrations to obtain the final schema.
- Generate a new carte for every HTTP request. Do not add carte caching in this implementation.
- Carte `clientScope` is `Scope.NONE`; its fixed `adminScope` is the exact set listed below.
- Required scope: `view-content`, `add-post`, `update-post`, `delete-own-content`, `view-feeds`, `update-feeds`,
  `view-profile`, `update-profile`, `view-settings`, `upload-public-media`, and `upload-private-media`.
- The configured importer address list is mandatory and non-empty; every entry is resolved at startup.
- The private key is exactly 32 bytes represented by 64 hexadecimal characters and must never be logged.
- Use field injection with `@Inject`; injected field names match their class with the first letter lowercased.
- Setters and getters only copy values. Put parsing and validation in the `@PostConstruct` method.
- Keep the required empty line after a class opening brace and before its closing brace.
- Follow strict TDD for behavior: write a failing test, observe the expected failure, implement minimally, rerun.
- Keep documentation under `doc/` and all documentation lines at or below 120 characters.

---

## File Map

- `src/main/java/org/moera/importer/config/MoeraAuthenticationProperties.java`: bind and validate global identity,
  private key, and addresses.
- `src/main/java/org/moera/importer/moera/MoeraCarteSourceFactory.java`: create destination-bound sources that sign a
  fresh carte per request with the fixed scope.
- `src/main/java/org/moera/importer/moera/MoeraClientFactory.java`: resolve nodes and select carte authentication.
- `src/main/resources/application.yml`: expose the three authentication properties through environment variables.
- `src/main/resources/db/migration/V007__global_moera_authentication.sql`: remove the obsolete credential column.
- `src/main/java/org/moera/importer/model/Destination.java`: remove per-destination credential state.
- `src/main/java/org/moera/importer/persistence/{DestinationRepository,JooqMappings}.java`: persist/map the reduced
  destination shape.
- `src/main/java/org/moera/importer/admin/{AdministrationModels,AdministrationService}.java`: remove `credentialRef`
  from the local API.
- `scripts/moera-import-admin`: remove the credential argument and output column.
- `doc/operations.md`: replace token provisioning with importer identity, IP, and Grants instructions.
- `.env.local.example`: show the new environment configuration names without supplying a real secret.

---

### Task 1: Global authentication configuration and fresh carte source

**Files:**

- Create: `src/main/java/org/moera/importer/config/MoeraAuthenticationProperties.java`
- Create: `src/test/java/org/moera/importer/config/MoeraAuthenticationPropertiesTest.java`
- Create: `src/main/java/org/moera/importer/moera/MoeraCarteSourceFactory.java`
- Create: `src/test/java/org/moera/importer/moera/MoeraCarteSourceFactoryTest.java`
- Modify: `src/main/resources/application.yml`
- Modify: `src/test/java/org/moera/importer/config/ApplicationConfigurationTest.java`
- Modify: `src/test/java/org/moera/importer/persistence/PostgresIntegrationTest.java`
- Modify: `src/main/java/org/moera/importer/moera/MoeraClientFactory.java`
- Modify: `src/test/java/org/moera/importer/moera/MoeraClientFactoryTest.java`
- Delete: `src/main/java/org/moera/importer/moera/MoeraCredentialStore.java`
- Delete: `src/test/java/org/moera/importer/moera/MoeraCredentialStoreTest.java`

**Interfaces:**

- Produces: `MoeraAuthenticationProperties.getName(): String`.
- Produces: `MoeraAuthenticationProperties.getPrivateSigningKey(): PrivateKey`.
- Produces: `MoeraAuthenticationProperties.getResolvedAddresses(): List<InetAddress>`.
- Produces: `MoeraCarteSourceFactory.create(String targetNodeName): CarteSource`.
- Preserves: `MoeraClientFactory.create(Destination): MoeraNode`.

- [ ] **Step 1: Write failing tests for binding defaults and validation**

Create `MoeraAuthenticationPropertiesTest` with a known valid secp256k1 scalar and loopback address. Test the default
name, successful parsing, and each required startup failure independently:

```java
private static final String VALID_KEY = "0".repeat(63) + "1";

@Test
void defaultsNameAndParsesRequiredIdentityMaterial() {
    MoeraAuthenticationProperties properties = new MoeraAuthenticationProperties();
    properties.setSigningKey(VALID_KEY);
    properties.setAddresses(List.of("127.0.0.1", "::1"));

    properties.validate();

    assertThat(properties.getName()).isEqualTo("importer_0");
    assertThat(CryptoUtil.rawPrivateKey((ECPrivateKey) properties.getPrivateSigningKey()))
        .containsExactly(HexFormat.of().parseHex(VALID_KEY));
    assertThat(properties.getResolvedAddresses())
        .extracting(InetAddress::getHostAddress)
        .containsExactly("127.0.0.1", "0:0:0:0:0:0:0:1");
}

@Test
void rejectsMissingKeyAndAddresses() {
    MoeraAuthenticationProperties properties = new MoeraAuthenticationProperties();

    assertThatThrownBy(properties::validate)
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Moera authentication signing key must be 64 hexadecimal characters");
}
```

Add separate tests that set the valid counterpart and reject an invalid full node name, 63-character key, non-hex key,
empty addresses, a blank address, and `256.256.256.256` as unresolvable.

Also add a failing `ApplicationConfigurationTest` case that supplies all three environment variables and expects the
corresponding `moera.authentication.*` YAML values:

```java
propertySources.addFirst(new MapPropertySource("environment", Map.of(
    "MOERA_AUTHENTICATION_NAME", "importer_7",
    "MOERA_AUTHENTICATION_SIGNING_KEY", VALID_KEY,
    "MOERA_AUTHENTICATION_ADDRESSES", "192.0.2.10,2001:db8::10"
)));

assertThat(resolver.getProperty("moera.authentication.name")).isEqualTo("importer_7");
assertThat(resolver.getProperty("moera.authentication.signing-key")).isEqualTo(VALID_KEY);
assertThat(resolver.getProperty("moera.authentication.addresses"))
    .isEqualTo("192.0.2.10,2001:db8::10");
```

- [ ] **Step 2: Run the configuration test and verify RED**

Run:

```bash
./mvnw -Dtest=MoeraAuthenticationPropertiesTest,ApplicationConfigurationTest test
```

Expected: compilation fails because `MoeraAuthenticationProperties` does not exist; after adding only the test class,
the YAML assertions also fail because the authentication keys are absent.

- [ ] **Step 3: Implement validated authentication properties and YAML binding**

Implement a focused component. Setters only assign fields; `validate()` performs all parsing and fills derived fields:

```java
@Component
@ConfigurationProperties("moera.authentication")
public class MoeraAuthenticationProperties {

    private String name = "importer_0";
    private String signingKey;
    private List<String> addresses = List.of();
    private PrivateKey privateSigningKey;
    private List<InetAddress> resolvedAddresses = List.of();

    @PostConstruct
    void validate() {
        validateName();
        byte[] rawKey;
        try {
            rawKey = HexFormat.of().parseHex(signingKey != null ? signingKey : "");
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                "Moera authentication signing key must be 64 hexadecimal characters", e
            );
        }
        if (rawKey.length != Rules.PRIVATE_KEY_LENGTH) {
            throw new IllegalStateException(
                "Moera authentication signing key must be 64 hexadecimal characters"
            );
        }
        try {
            privateSigningKey = CryptoUtil.rawToPrivateKey(rawKey);
            CryptoUtil.privateToPublicKey((ECPrivateKey) privateSigningKey);
        } catch (RuntimeException e) {
            throw new IllegalStateException("Moera authentication signing key is invalid", e);
        }
        resolvedAddresses = resolveAddresses();
    }
}
```

`validateName()` parses with `NodeName.parse`, requires a non-negative generation, checks the base name with
`Rules.isNameValid`, limits the full name to 135 characters, and requires canonical full form:

```java
if (!NodeName.expand(name).equals(name)) {
    throw new IllegalStateException("Moera authentication name must include its generation");
}
```

`resolveAddresses()` rejects `ObjectUtils.isEmpty(addresses)`, blank entries, or any `UnknownHostException`, expands
each configured value through `InetAddress.getAllByName`, and returns `List.copyOf(resolved)`.

Add the following application properties:

```yaml
moera:
  authentication:
    name: ${MOERA_AUTHENTICATION_NAME:importer_0}
    signing-key: ${MOERA_AUTHENTICATION_SIGNING_KEY:}
    addresses: ${MOERA_AUTHENTICATION_ADDRESSES:}
```

Keep Spring integration tests bootable by adding valid test values to `PostgresIntegrationTest.configureDataSource`:

```java
registry.add("moera.authentication.signing-key", () -> "0".repeat(63) + "1");
registry.add("moera.authentication.addresses", () -> "127.0.0.1");
```

- [ ] **Step 4: Run the configuration test and verify GREEN**

Run:

```bash
./mvnw -Dtest=MoeraAuthenticationPropertiesTest,ApplicationConfigurationTest test
```

Expected: all configuration tests pass.

- [ ] **Step 5: Write a failing behavioral test for generated cartes**

Create `MoeraCarteSourceFactoryTest`. Use a fixed `Clock`, call the returned real `CarteSource` twice, decode each value
with `Util.base64urldecode` and `CryptoUtil.restoreFingerprint`, and assert the actual fingerprint fields:

```java
assertThat(first).isNotEqualTo(second);
assertThat(fingerprint.get("owner_name")).isEqualTo("importer_0");
assertThat(fingerprint.get("node_name")).isEqualTo("destination_0");
assertThat(fingerprint.get("client_scope")).isEqualTo(Scope.NONE.getMask());
assertThat(fingerprint.get("admin_scope")).isEqualTo(requiredScope());
assertThat((List<InetAddress>) fingerprint.get("addresses"))
    .containsExactly(InetAddress.getByName("192.0.2.10"), InetAddress.getByName("2001:db8::10"));
```

Derive `requiredScope()` in the test only from the eleven explicit `Scope` constants in the approved list. Also assert
the beginning equals the fixed clock and deadline equals `Carte.getDeadline(beginning)`.

- [ ] **Step 6: Run the carte-source test and verify RED**

Run:

```bash
./mvnw -Dtest=MoeraCarteSourceFactoryTest test
```

Expected: compilation fails because `MoeraCarteSourceFactory` does not exist.

- [ ] **Step 7: Implement a destination-bound source that signs on every call**

Create the component with the fixed scope and no cache:

```java
@Component
public class MoeraCarteSourceFactory {

    private static final long ADMIN_SCOPE = Scope.VIEW_CONTENT.getMask()
        | Scope.ADD_POST.getMask()
        | Scope.UPDATE_POST.getMask()
        | Scope.DELETE_OWN_CONTENT.getMask()
        | Scope.VIEW_FEEDS.getMask()
        | Scope.UPDATE_FEEDS.getMask()
        | Scope.VIEW_PROFILE.getMask()
        | Scope.UPDATE_PROFILE.getMask()
        | Scope.VIEW_SETTINGS.getMask()
        | Scope.UPLOAD_PUBLIC_MEDIA.getMask()
        | Scope.UPLOAD_PRIVATE_MEDIA.getMask();

    @Inject
    private Clock clock;

    @Inject
    private MoeraAuthenticationProperties moeraAuthenticationProperties;

    public CarteSource create(String targetNodeName) {
        return () -> Carte.generate(
            moeraAuthenticationProperties.getName(),
            moeraAuthenticationProperties.getResolvedAddresses(),
            clock.instant(),
            moeraAuthenticationProperties.getPrivateSigningKey(),
            targetNodeName,
            Scope.NONE.getMask(),
            ADMIN_SCOPE
        );
    }
}
```

The lambda is intentional because it must invoke `clock.instant()` and `Carte.generate(...)` anew for every request.

- [ ] **Step 8: Run the carte-source test and verify GREEN**

Run:

```bash
./mvnw -Dtest=MoeraCarteSourceFactoryTest test
```

Expected: both cartes decode with the required fields and differ because each has a fresh salt.

- [ ] **Step 9: Change the client-factory test to require peer authentication**

Replace token-store setup with a stub `CarteSource` returned by a mocked `MoeraCarteSourceFactory`. Assert observable
client state and keep the existing resolve-every-time assertion:

```java
when(moeraCarteSourceFactory.create("alice")).thenReturn(() -> "signed-carte");

MoeraNode first = factory.create(destination);

assertThat(ReflectionTestUtils.getField(first, "carteSource")).isNotNull();
assertThat(ReflectionTestUtils.getField(first, "token")).isNull();
assertThat(ReflectionTestUtils.getField(first, "authMethod")).isEqualTo(NodeAuth.PEER);
```

Keep the current `Destination` constructor shape in this task, using a visibly unused value such as `"env:UNUSED"`;
Task 2 removes that component everywhere together with the Flyway migration.

- [ ] **Step 10: Run the client-factory test and verify RED**

Run:

```bash
./mvnw -Dtest=MoeraClientFactoryTest test
```

Expected: failure because the factory still resolves a token and selects `NodeAuth.ADMIN`.

- [ ] **Step 11: Integrate carte authentication and delete the token store**

Replace the credential-store field and token calls in `MoeraClientFactory`:

```java
@Inject
private MoeraCarteSourceFactory moeraCarteSourceFactory;

public MoeraNode create(Destination destination) {
    String nodeUrl = resolve(destination.nodeName());
    MoeraNode moeraNode = new MoeraNode(new OkHttpTransport(), nodeUrl);
    moeraNode.carteSource(moeraCarteSourceFactory.create(destination.nodeName()));
    moeraNode.auth();
    moeraNode.userAgent(USER_AGENT);
    return moeraNode;
}
```

Delete `MoeraCredentialStore` and its tests. Update the development-naming-server context test to provide a mocked
`MoeraCarteSourceFactory`; it must no longer instantiate `MoeraCredentialStore`.

- [ ] **Step 12: Run authentication tests and verify GREEN**

Run:

```bash
./mvnw -Dtest=MoeraAuthenticationPropertiesTest,MoeraCarteSourceFactoryTest,MoeraClientFactoryTest test
```

Expected: all tests pass with no token-store references.

- [ ] **Step 13: Commit the authentication boundary**

```bash
git add src/main/java/org/moera/importer/config/MoeraAuthenticationProperties.java \
  src/test/java/org/moera/importer/config/MoeraAuthenticationPropertiesTest.java \
  src/main/java/org/moera/importer/moera/MoeraCarteSourceFactory.java \
  src/test/java/org/moera/importer/moera/MoeraCarteSourceFactoryTest.java \
  src/main/java/org/moera/importer/moera/MoeraClientFactory.java \
  src/test/java/org/moera/importer/moera/MoeraClientFactoryTest.java \
  src/main/resources/application.yml \
  src/test/java/org/moera/importer/config/ApplicationConfigurationTest.java \
  src/test/java/org/moera/importer/persistence/PostgresIntegrationTest.java \
  src/main/java/org/moera/importer/moera/MoeraCredentialStore.java \
  src/test/java/org/moera/importer/moera/MoeraCredentialStoreTest.java
git commit -m "feat: authenticate to Moera with cartes"
```

---

### Task 2: Remove per-destination credentials from schema, domain, and administration API

**Files:**

- Create: `src/main/resources/db/migration/V007__global_moera_authentication.sql`
- Modify: `src/test/java/org/moera/importer/persistence/SchemaMigrationTest.java`
- Modify: `src/main/java/org/moera/importer/model/Destination.java`
- Modify: `src/main/java/org/moera/importer/persistence/JooqMappings.java`
- Modify: `src/main/java/org/moera/importer/persistence/DestinationRepository.java`
- Modify: `src/main/java/org/moera/importer/admin/AdministrationModels.java`
- Modify: `src/main/java/org/moera/importer/admin/AdministrationService.java`
- Modify: `src/test/java/org/moera/importer/admin/AdministrationApiTest.java`
- Modify: every Java test returned by
  `rg -l 'new Destination\\(|DESTINATIONS\\.CREDENTIAL_REF' src/test/java`

**Interfaces:**

- Changes: `Destination` removes `String credentialRef`.
- Changes: `DestinationRepository.create(String nodeName, boolean profileSync): Destination`.
- Changes: `DestinationCreate(String nodeName, boolean profileSync)`.
- Changes: `DestinationView(long id, String nodeName, boolean profileSync)`.

- [ ] **Step 1: Write a failing V007 migration test**

Add a test that migrates an isolated schema through V006, inserts a destination with a real legacy credential value,
migrates through current, and verifies both row preservation and column removal:

```java
@Test
void v007RemovesCredentialReferenceWithoutRemovingDestinations() throws SQLException {
    String schema = "global_auth_" + UUID.randomUUID().toString().replace("-", "");
    try {
        flyway(schema, MigrationVersion.fromVersion("6")).migrate();
        try (Connection connection = openConnection(); var statement = connection.createStatement()) {
            statement.execute("""
                INSERT INTO %s.destinations (node_name, credential_ref)
                VALUES ('destination.example', 'env:LEGACY_TOKEN')
                """.formatted(schema));
        }

        flyway(schema, null).migrate();

        assertThat(columns(schema, "destinations")).doesNotContain("credential_ref");
        assertThat(queryStrings(schema, "SELECT node_name FROM %s.destinations".formatted(schema)))
            .containsExactly("destination.example");
    } finally {
        dropSchema(schema);
    }
}
```

Add schema-aware overloads of the existing query helpers rather than duplicating JDBC loops. Change the two tests
whose names start with `v006` to migrate explicitly to version `6`; their historical V006 assertions must continue to
see `credential_ref`. Update final-schema assertions to require that the current `destinations` table does not contain
the column.

- [ ] **Step 2: Run the migration test and verify RED**

Run:

```bash
./mvnw -Dtest=SchemaMigrationTest#v007RemovesCredentialReferenceWithoutRemovingDestinations test
```

Expected: failure because V007 does not exist and the final schema still contains `credential_ref`.

- [ ] **Step 3: Add the minimal migration**

Create exactly:

```sql
ALTER TABLE destinations
    DROP COLUMN credential_ref;
```

- [ ] **Step 4: Update the domain and persistence mapping for generated V007 schema**

Remove the credential component and null check from `Destination`:

```java
public record Destination(
    long id,
    String nodeName,
    boolean profileSync,
    String profileFingerprint,
    String profileSourceEmblemId,
    String avatarId,
    String avatarMediaId,
    String avatarShape,
    Instant createdAt,
    Instant updatedAt
) {

    public Destination(long id, String nodeName, Instant createdAt, Instant updatedAt) {
        this(id, nodeName, true, null, null, null, null, null, createdAt, updatedAt);
    }
}
```

Remove `record.getCredentialRef()` from `JooqMappings.destination`. Change repository creation to:

```java
public Destination create(String nodeName, boolean profileSync) {
    DestinationsRecord record = dslContext.insertInto(DESTINATIONS)
        .set(DESTINATIONS.NODE_NAME, nodeName)
        .set(DESTINATIONS.PROFILE_SYNC, profileSync)
        .returning()
        .fetchSingle();
    return jooqMappings.destination(record);
}
```

- [ ] **Step 5: Write the credential-free administration API assertions**

Change destination creation requests to contain only `nodeName` and optional `profileSync`. Assert the response does
not expose the deleted field and the row is persisted:

```java
HttpResponse<String> destinationCreated = request("POST", "/api/destinations", objectNode()
    .put("nodeName", "destination-node"));

assertThat(destinationCreated.statusCode()).isEqualTo(201);
assertThat(json(destinationCreated).has("credentialRef")).isFalse();
assertThat(json(destinationCreated).path("profileSync").asBoolean()).isTrue();
```

Keep the invalid-name test but remove the now-irrelevant raw-credential input.

- [ ] **Step 6: Run the API test and verify RED**

Run:

```bash
./mvnw -Dtest=AdministrationApiTest test
```

Expected: compilation or assertion failure because administration records and service still require `credentialRef`.

- [ ] **Step 7: Remove `credentialRef` from API records and service validation**

Use these records:

```java
public record DestinationCreate(String nodeName, boolean profileSync) {

    @JsonCreator
    public DestinationCreate(
        @JsonProperty("nodeName") String nodeName,
        @JsonProperty("profileSync") Boolean profileSync
    ) {
        this(nodeName, profileSync == null || profileSync);
    }
}

public record DestinationView(long id, String nodeName, boolean profileSync) {
}
```

Call `destinationRepository.create(request.nodeName(), request.profileSync())`, map only the remaining fields, remove
credential-reference imports/constants/helpers, and keep existing node-name validation unchanged.

- [ ] **Step 8: Mechanically update Java fixtures and direct inserts**

For every match from the file-list command, remove the credential argument from `new Destination(...)` and delete
`.set(DESTINATIONS.CREDENTIAL_REF, "env:TEST")`. Preserve every other fixture value and assertion. Examples:

```java
Destination destination = new Destination(1, "alice", NOW, NOW);

Destination destinationWithAvatar = new Destination(
    1, "alice", true, null, null, "avatar-id", "avatar-media-id", "circle", NOW, NOW
);
```

Do not remove `credentialRef` JSON from V001-to-V006 historical migration fixtures; those fixtures prove the old
migrations still upgrade correctly. Only current-schema code and assertions change.

- [ ] **Step 9: Run migration, API, and compile verification**

Run:

```bash
./mvnw -Dtest=SchemaMigrationTest,AdministrationApiTest test
./mvnw -DskipTests compile
```

Expected: V007 preserves destination rows, current jOOQ generation has no credential accessor, and both tests pass.

- [ ] **Step 10: Commit the schema and API removal**

```bash
git add src/main/resources/db/migration/V007__global_moera_authentication.sql \
  src/main/java/org/moera/importer/model/Destination.java \
  src/main/java/org/moera/importer/persistence/JooqMappings.java \
  src/main/java/org/moera/importer/persistence/DestinationRepository.java \
  src/main/java/org/moera/importer/admin/AdministrationModels.java \
  src/main/java/org/moera/importer/admin/AdministrationService.java \
  src/test/java
git commit -m "feat: remove destination credential references"
```

---

### Task 3: Simplify the host-local administration CLI

**Files:**

- Modify: `scripts/moera-import-admin`
- Modify: `scripts/test_moera_import_admin.py`

**Interfaces:**

- Changes CLI syntax from `destination add NODE_NAME CREDENTIAL_REF` to `destination add NODE_NAME`.
- Changes destination JSON body to `{nodeName, profileSync}`.

- [ ] **Step 1: Change the CLI test to the credential-free contract**

Replace the destination test arguments and expected request:

```python
arguments = MODULE.parser().parse_args([
    "destination", "add", "news", "--no-profile-sync"
])

self.assertEqual(client.requests, [(
    "POST",
    "/api/destinations",
    {
        "nodeName": "news",
        "profileSync": False,
    },
)])
```

Add a destination-list test whose fake row contains only `id`, `nodeName`, and `profileSync`; assert the output does
not contain `CREDENTIAL REF`.

- [ ] **Step 2: Run the Python test and verify RED**

Run:

```bash
python3 -m unittest scripts/test_moera_import_admin.py
```

Expected: argument parsing requires `credential_ref`, and list output still contains the credential column.

- [ ] **Step 3: Remove the argument, JSON field, and output column**

Implement the reduced request and parser:

```python
created = client.request("POST", "/api/destinations", {
    "nodeName": args.node_name,
    "profileSync": args.profile_sync,
})
```

```python
destination_add.add_argument("node_name")
destination_add.add_argument(
    "--no-profile-sync",
    dest="profile_sync",
    action="store_false",
    help="do not synchronize the Telegram channel with the Moera profile",
)
```

The list columns become `ID`, `NODE NAME`, and `PROFILE SYNC` only.

- [ ] **Step 4: Run the Python test and verify GREEN**

Run:

```bash
python3 -m unittest scripts/test_moera_import_admin.py
```

Expected: all CLI tests pass.

- [ ] **Step 5: Commit the CLI contract**

```bash
git add scripts/moera-import-admin scripts/test_moera_import_admin.py
git commit -m "feat: simplify destination administration"
```

---

### Task 4: Replace token operations with carte and Grants operations

**Files:**

- Modify: `doc/operations.md`
- Modify: `.env.local.example`

**Interfaces:**

- Documents: `MOERA_AUTHENTICATION_NAME`, `MOERA_AUTHENTICATION_SIGNING_KEY`, and
  `MOERA_AUTHENTICATION_ADDRESSES`.
- Documents: exact required Grants command for `importer_0`.

- [ ] **Step 1: Rewrite the Moera connection runbook**

Remove the dedicated-token creation, credential-reference storage, rotation, and token troubleshooting sections.
Document this deployment configuration instead:

```dotenv
MOERA_NAMING_SERVER=main
MOERA_AUTHENTICATION_NAME=importer_0
MOERA_AUTHENTICATION_SIGNING_KEY=replace-with-64-hex-character-private-signing-key
MOERA_AUTHENTICATION_ADDRESSES=203.0.113.10,2001:db8::10
```

State explicitly that the name and matching public signing key must already be registered in the selected naming
environment, addresses must be the source IPs observed by destination nodes, and changing any value requires restart.
Warn that the private key belongs in deployment secret storage and must not enter PostgreSQL, logs, or source control.

Add the exact grant command, keeping the comma-separated scopes in one shell argument:

```bash
IMPORTER_SCOPES='view-content,add-post,update-post,delete-own-content,view-feeds,'\
'update-feeds,view-profile,update-profile,view-settings,upload-public-media,upload-private-media'
moctl -N my-node-name grant grant importer_0 "$IMPORTER_SCOPES"
```

Explain that `my-node-name` is the destination granting rights and `importer_0` is the configured importer identity.
Show `moctl -N my-node-name grant show importer_0` as verification. Update destination examples to:

```bash
./scripts/moera-import-admin destination add my-node-name
./scripts/moera-import-admin destination add aggregate-node --no-profile-sync
```

Update the object description and troubleshooting text so destinations contain only a node name plus importer-owned
delivery/profile state. Authentication failures should direct operators to name registration, public/private key
match, Grants, configured addresses versus observed source IP, clock synchronization, and naming environment.

- [ ] **Step 2: Extend the local environment example**

Add non-secret structure without a usable key:

```dotenv
MOERA_AUTHENTICATION_NAME=importer_0
MOERA_AUTHENTICATION_SIGNING_KEY=replace-with-64-hex-character-private-signing-key
MOERA_AUTHENTICATION_ADDRESSES=127.0.0.1
```

- [ ] **Step 3: Verify documentation formatting and stale terms**

Run:

```bash
awk 'length($0) > 120 { print FNR ":" length($0) ":" $0 }' \
  doc/operations.md doc/architecture/import-service.md doc/superpowers/plans/2026-09-01-carte-authentication.md
rg -n 'credentialRef|credential_ref|MOERA_ADMIN_TOKEN|admin token' doc/operations.md .env.local.example
git diff --check
```

Expected: no overlong lines, no stale token/credential instructions in operational files, and no whitespace errors.

- [ ] **Step 4: Commit operations documentation**

```bash
git add doc/operations.md .env.local.example
git commit -m "docs: document importer carte provisioning"
```

---

### Task 5: Full regression verification

**Files:**

- Verify only; no planned production changes.

**Interfaces:**

- Confirms all Java, SQL migration, Python CLI, formatting, and documentation contracts together.

- [ ] **Step 1: Confirm no obsolete application references remain**

Run:

```bash
rg -n 'MoeraCredentialStore|credentialRef|CREDENTIAL_REF|getCredentialRef' src/main src/test scripts
```

Expected: only intentional `credentialRef` text inside historical V001-to-V006 migration fixtures may remain under
`SchemaMigrationTest`; no current model, generated-table consumer, API, or script reference remains.

- [ ] **Step 2: Run the Python CLI suite**

Run:

```bash
python3 -m unittest scripts/test_moera_import_admin.py
```

Expected: PASS.

- [ ] **Step 3: Run Maven clean verification with PostgreSQL**

With the project's documented Docker-compatible Testcontainers endpoint available, run:

```bash
./mvnw clean verify
```

Expected: checkstyle, compilation, jOOQ generation from V001-V007, unit tests, and PostgreSQL integration tests all
pass.

- [ ] **Step 4: Inspect final diff and repository state**

Run:

```bash
git diff --check
git status --short
git log -5 --oneline
```

Expected: no uncommitted implementation changes, no whitespace errors, and separate commits for authentication,
schema/API, CLI, and operations documentation following the design commit `ecf4f5b`.
