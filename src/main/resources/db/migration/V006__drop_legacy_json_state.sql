-- [jooq ignore start]
CREATE FUNCTION legacy_html_escape(value text)
RETURNS text
LANGUAGE sql
IMMUTABLE
STRICT
AS $function$
    SELECT replace(
        replace(
            replace(
                replace(value, '&', '&amp;'),
                '<', '&lt;'
            ),
            '>', '&gt;'
        ),
        '"', '&quot;'
    )
$function$;

CREATE FUNCTION legacy_utf16_boundary(value text, requested_units integer)
RETURNS integer
LANGUAGE plpgsql
IMMUTABLE
STRICT
AS $function$
DECLARE
    character_position integer := 0;
    utf16_position integer := 0;
    character_value text;
BEGIN
    IF requested_units < 0 THEN
        RETURN NULL;
    END IF;
    IF requested_units = 0 THEN
        RETURN 0;
    END IF;
    FOR character_value IN
        SELECT character
        FROM regexp_split_to_table(value, '') AS character
    LOOP
        character_position := character_position + 1;
        utf16_position := utf16_position + CASE
            WHEN ascii(character_value) > 65535 THEN 2
            ELSE 1
        END;
        IF utf16_position = requested_units THEN
            RETURN character_position;
        END IF;
        IF utf16_position > requested_units THEN
            RETURN NULL;
        END IF;
    END LOOP;
    RETURN CASE WHEN utf16_position = requested_units THEN character_position ELSE NULL END;
END
$function$;

CREATE FUNCTION legacy_channel_description_html(metadata jsonb)
RETURNS text
LANGUAGE plpgsql
IMMUTABLE
STRICT
AS $function$
DECLARE
    description text := coalesce(metadata ->> 'description', '');
    linked text := '';
    position integer := 0;
    entity jsonb;
    entity_start integer;
    entity_end integer;
    visible text;
    href text;
    rendered text;
BEGIN
    IF description ~ '^\s*$' THEN
        RETURN '';
    END IF;
    FOR entity IN
        SELECT item.value
        FROM jsonb_array_elements(coalesce(metadata -> 'descriptionEntities', '[]'::jsonb))
            WITH ORDINALITY AS item(value, position)
        ORDER BY (item.value ->> 'offset')::integer, item.position
    LOOP
        entity_start := legacy_utf16_boundary(description, (entity ->> 'offset')::integer);
        entity_end := legacy_utf16_boundary(
            description,
            (entity ->> 'offset')::integer + (entity ->> 'length')::integer
        );
        IF entity_start IS NULL OR entity_end IS NULL OR entity_start < position THEN
            CONTINUE;
        END IF;
        linked := linked || legacy_html_escape(substr(description, position + 1, entity_start - position));
        visible := substr(description, entity_start + 1, entity_end - entity_start);
        href := CASE entity ->> 'type'
            WHEN 'URL' THEN visible
            WHEN 'EMAIL' THEN 'mailto:' || visible
            WHEN 'MENTION' THEN 'https://t.me/' || substr(visible, 2)
        END;
        linked := linked || '<a href="' || legacy_html_escape(href) || '">'
            || legacy_html_escape(visible) || '</a>';
        position := entity_end;
    END LOOP;
    linked := linked || legacy_html_escape(substr(description, position + 1));
    linked := replace(replace(linked, E'\r\n', E'\n'), E'\r', E'\n');

    SELECT coalesce(string_agg(
        '<p>' || replace(paragraph.value, E'\n', '<br>') || '</p>',
        '' ORDER BY paragraph.position
    ), '')
    INTO rendered
    FROM regexp_split_to_table(linked, E'\n\s*\n')
        WITH ORDINALITY AS paragraph(value, position)
    WHERE paragraph.value !~ '^\s*$';
    RETURN rendered;
END
$function$;

DO $validation$
DECLARE
    invalid record;
BEGIN
    SELECT id, 'initialImportMode' AS field
    INTO invalid
    FROM channels
    WHERE source_data ? 'initialImportMode'
      AND (
          jsonb_typeof(source_data -> 'initialImportMode') <> 'string'
          OR source_data ->> 'initialImportMode' NOT IN ('RECENT', 'FULL')
      )
    LIMIT 1;
    IF FOUND THEN
        RAISE EXCEPTION 'Cannot migrate channels row % field %', invalid.id, invalid.field;
    END IF;

    SELECT id, 'metadata' AS field
    INTO invalid
    FROM channels
    WHERE jsonb_typeof(metadata) <> 'object'
       OR (
           metadata <> '{}'::jsonb
           AND (
               jsonb_typeof(metadata -> 'title') <> 'string'
               OR (metadata ? 'description' AND jsonb_typeof(metadata -> 'description') <> 'string')
               OR (metadata ? 'descriptionEntities'
                   AND jsonb_typeof(metadata -> 'descriptionEntities') <> 'array')
               OR (metadata ? 'publicUsername'
                   AND jsonb_typeof(metadata -> 'publicUsername') <> 'string')
               OR (metadata ? 'emblem' AND jsonb_typeof(metadata -> 'emblem') <> 'object')
           )
       )
    LIMIT 1;
    IF FOUND THEN
        RAISE EXCEPTION 'Cannot migrate channels row % field %', invalid.id, invalid.field;
    END IF;

    SELECT channels.id, metadata_field.name AS field
    INTO invalid
    FROM channels
    CROSS JOIN LATERAL jsonb_object_keys(metadata) AS metadata_field(name)
    WHERE metadata_field.name <> ALL (
        ARRAY['title', 'description', 'descriptionEntities', 'publicUsername', 'emblem']
    )
    LIMIT 1;
    IF FOUND THEN
        RAISE EXCEPTION 'Cannot migrate channels row % field metadata.%', invalid.id, invalid.field;
    END IF;

    SELECT channels.id, emblem_field.name AS field
    INTO invalid
    FROM channels
    CROSS JOIN LATERAL jsonb_object_keys(coalesce(metadata -> 'emblem', '{}'::jsonb))
        AS emblem_field(name)
    WHERE emblem_field.name <> ALL (ARRAY['remoteId', 'uniqueId', 'mimeType'])
    LIMIT 1;
    IF FOUND THEN
        RAISE EXCEPTION 'Cannot migrate channels row % field emblem.%', invalid.id, invalid.field;
    END IF;

    SELECT id, 'emblem' AS field
    INTO invalid
    FROM channels
    WHERE metadata ? 'emblem'
      AND (
          jsonb_typeof(metadata #> '{emblem,remoteId}') <> 'string'
          OR jsonb_typeof(metadata #> '{emblem,uniqueId}') <> 'string'
          OR jsonb_typeof(metadata #> '{emblem,mimeType}') <> 'string'
      )
    LIMIT 1;
    IF FOUND THEN
        RAISE EXCEPTION 'Cannot migrate channels row % field %', invalid.id, invalid.field;
    END IF;

    SELECT channels.id, 'descriptionEntities' AS field
    INTO invalid
    FROM channels
    CROSS JOIN LATERAL jsonb_array_elements(
        CASE
            WHEN jsonb_typeof(metadata -> 'descriptionEntities') = 'array'
                THEN metadata -> 'descriptionEntities'
            ELSE '[]'::jsonb
        END
    ) AS entity(value)
    WHERE jsonb_typeof(entity.value) <> 'object'
       OR entity.value - ARRAY['offset', 'length', 'type'] <> '{}'::jsonb
       OR (entity.value ->> 'offset') !~ '^-?[0-9]+$'
       OR (entity.value ->> 'length') !~ '^[0-9]+$'
       OR (entity.value ->> 'length')::integer <= 0
       OR jsonb_typeof(entity.value -> 'type') <> 'string'
       OR entity.value ->> 'type' NOT IN ('URL', 'EMAIL', 'MENTION')
    LIMIT 1;
    IF FOUND THEN
        RAISE EXCEPTION 'Cannot migrate channels row % field %', invalid.id, invalid.field;
    END IF;

    SELECT messages.id, message_field.name AS field
    INTO invalid
    FROM messages
    CROSS JOIN LATERAL jsonb_object_keys(
        CASE
            WHEN jsonb_typeof(source_data -> 'forwardAttribution') = 'object'
                THEN source_data -> 'forwardAttribution'
            ELSE '{}'::jsonb
        END
    ) AS message_field(name)
    WHERE message_field.name <> ALL (ARRAY['name', 'url'])
    LIMIT 1;
    IF FOUND THEN
        RAISE EXCEPTION 'Cannot migrate messages row % field forwardAttribution.%', invalid.id, invalid.field;
    END IF;

    SELECT id, 'source_data' AS field
    INTO invalid
    FROM messages
    WHERE (
        source_data ? 'externalSourceUri'
        AND jsonb_typeof(source_data -> 'externalSourceUri') <> 'string'
    ) OR (
        source_data ? 'forwardAttribution'
        AND (
            jsonb_typeof(source_data -> 'forwardAttribution') <> 'object'
            OR jsonb_typeof(source_data #> '{forwardAttribution,name}') <> 'string'
            OR (source_data #> '{forwardAttribution,url}' IS NOT NULL
                AND jsonb_typeof(source_data #> '{forwardAttribution,url}') <> 'string')
        )
    )
    LIMIT 1;
    IF FOUND THEN
        RAISE EXCEPTION 'Cannot migrate messages row % field %', invalid.id, invalid.field;
    END IF;

    SELECT id, 'config' AS field
    INTO invalid
    FROM destinations
    WHERE jsonb_typeof(config) <> 'object'
       OR (config ? 'credentialRef' AND (
           jsonb_typeof(config -> 'credentialRef') <> 'string'
           OR btrim(config ->> 'credentialRef') = ''
       ))
       OR (config ? 'profileSync' AND jsonb_typeof(config -> 'profileSync') <> 'boolean')
       OR NOT (config ? 'credentialRef')
    LIMIT 1;
    IF FOUND THEN
        RAISE EXCEPTION 'Cannot migrate destinations row % field %', invalid.id, invalid.field;
    END IF;

    SELECT destinations.id, config_field.name AS field
    INTO invalid
    FROM destinations
    CROSS JOIN LATERAL jsonb_object_keys(config) AS config_field(name)
    WHERE config_field.name <> ALL (ARRAY['credentialRef', 'profileSync'])
    LIMIT 1;
    IF FOUND THEN
        RAISE EXCEPTION 'Cannot migrate destinations row % field config.%', invalid.id, invalid.field;
    END IF;

    SELECT id, 'data' AS field
    INTO invalid
    FROM destinations
    WHERE jsonb_typeof(data) <> 'object'
       OR (data ? 'profile' AND jsonb_typeof(data -> 'profile') <> 'object')
    LIMIT 1;
    IF FOUND THEN
        RAISE EXCEPTION 'Cannot migrate destinations row % field %', invalid.id, invalid.field;
    END IF;

    SELECT destinations.id, data_field.name AS field
    INTO invalid
    FROM destinations
    CROSS JOIN LATERAL jsonb_object_keys(data) AS data_field(name)
    WHERE data_field.name <> 'profile'
    LIMIT 1;
    IF FOUND THEN
        RAISE EXCEPTION 'Cannot migrate destinations row % field data.%', invalid.id, invalid.field;
    END IF;

    SELECT destinations.id, profile_field.name AS field
    INTO invalid
    FROM destinations
    CROSS JOIN LATERAL jsonb_object_keys(coalesce(data -> 'profile', '{}'::jsonb))
        AS profile_field(name)
    WHERE profile_field.name <> ALL (
        ARRAY['fingerprint', 'sourceEmblemUniqueId', 'avatarId', 'avatarMediaId', 'avatarShape']
    )
    LIMIT 1;
    IF FOUND THEN
        RAISE EXCEPTION 'Cannot migrate destinations row % field profile.%', invalid.id, invalid.field;
    END IF;

    SELECT id, 'data.profile' AS field
    INTO invalid
    FROM destinations
    WHERE data ? 'profile'
      AND (
          (data #> '{profile,fingerprint}' IS NOT NULL
              AND jsonb_typeof(data #> '{profile,fingerprint}') <> 'string')
          OR (data #> '{profile,sourceEmblemUniqueId}' IS NOT NULL
              AND jsonb_typeof(data #> '{profile,sourceEmblemUniqueId}') <> 'string')
          OR (data #> '{profile,avatarId}' IS NOT NULL
              AND jsonb_typeof(data #> '{profile,avatarId}') <> 'string')
          OR (data #> '{profile,avatarMediaId}' IS NOT NULL
              AND jsonb_typeof(data #> '{profile,avatarMediaId}') <> 'string')
          OR (data #> '{profile,avatarShape}' IS NOT NULL
              AND jsonb_typeof(data #> '{profile,avatarShape}') <> 'string')
      )
    LIMIT 1;
    IF FOUND THEN
        RAISE EXCEPTION 'Cannot migrate destinations row % field %', invalid.id, invalid.field;
    END IF;

    SELECT mirrors.id, config_field.name AS field
    INTO invalid
    FROM mirrors
    CROSS JOIN LATERAL jsonb_object_keys(
        CASE WHEN jsonb_typeof(config) = 'object' THEN config ELSE '{}'::jsonb END
    ) AS config_field(name)
    WHERE config_field.name <> 'feeds'
    LIMIT 1;
    IF FOUND THEN
        RAISE EXCEPTION 'Cannot migrate mirrors row % field config.%', invalid.id, invalid.field;
    END IF;

    SELECT id, 'config.feeds' AS field
    INTO invalid
    FROM mirrors
    WHERE jsonb_typeof(config) <> 'object'
       OR CASE
           WHEN jsonb_typeof(config -> 'feeds') IS DISTINCT FROM 'array' THEN true
           ELSE jsonb_array_length(config -> 'feeds') = 0
               OR EXISTS (
                   SELECT 1
                   FROM jsonb_array_elements(config -> 'feeds') AS feed(value)
                   WHERE jsonb_typeof(feed.value) <> 'string' OR btrim(feed.value #>> '{}') = ''
               )
               OR (SELECT count(*) FROM jsonb_array_elements_text(config -> 'feeds'))
                  <> (SELECT count(DISTINCT value)
                      FROM jsonb_array_elements_text(config -> 'feeds') AS feed(value))
       END
    LIMIT 1;
    IF FOUND THEN
        RAISE EXCEPTION 'Cannot migrate mirrors row % field %', invalid.id, invalid.field;
    END IF;

    SELECT publications.id, destination_field.name AS field
    INTO invalid
    FROM publications
    CROSS JOIN LATERAL jsonb_object_keys(
        CASE
            WHEN jsonb_typeof(destination_data) = 'object' THEN destination_data
            ELSE '{}'::jsonb
        END
    ) AS destination_field(name)
    WHERE destination_field.name <> ALL (ARRAY['pinned', 'storyIds'])
    LIMIT 1;
    IF FOUND THEN
        RAISE EXCEPTION 'Cannot migrate publications row % field destination_data.%', invalid.id, invalid.field;
    END IF;

    SELECT id, 'destination_data' AS field
    INTO invalid
    FROM publications
    WHERE jsonb_typeof(destination_data) <> 'object'
       OR (destination_data ? 'pinned' AND jsonb_typeof(destination_data -> 'pinned') <> 'boolean')
       OR (destination_data ? 'storyIds'
           AND jsonb_typeof(destination_data -> 'storyIds') <> 'object')
    LIMIT 1;
    IF FOUND THEN
        RAISE EXCEPTION 'Cannot migrate publications row % field %', invalid.id, invalid.field;
    END IF;

    SELECT publications.id, story.key AS field
    INTO invalid
    FROM publications
    CROSS JOIN LATERAL jsonb_each(
        CASE
            WHEN jsonb_typeof(destination_data -> 'storyIds') = 'object'
                THEN destination_data -> 'storyIds'
            ELSE '{}'::jsonb
        END
    ) AS story(key, value)
    WHERE btrim(story.key) = ''
       OR jsonb_typeof(story.value) <> 'string'
       OR btrim(story.value #>> '{}') = ''
    LIMIT 1;
    IF FOUND THEN
        RAISE EXCEPTION 'Cannot migrate publications row % field storyIds.%', invalid.id, invalid.field;
    END IF;

    SELECT publication_id, media_id, destination_field.name AS field
    INTO invalid
    FROM publication_media
    CROSS JOIN LATERAL jsonb_object_keys(
        CASE
            WHEN jsonb_typeof(destination_data) = 'object' THEN destination_data
            ELSE '{}'::jsonb
        END
    ) AS destination_field(name)
    WHERE destination_field.name <> ALL (ARRAY['hash', 'compressionPending'])
    LIMIT 1;
    IF FOUND THEN
        RAISE EXCEPTION 'Cannot migrate publication_media row (publication_id=%, media_id=%) '
            'field destination_data.%', invalid.publication_id, invalid.media_id, invalid.field;
    END IF;

    SELECT publication_id, media_id, 'destination_data' AS field
    INTO invalid
    FROM publication_media
    WHERE jsonb_typeof(destination_data) <> 'object'
       OR (destination_data ? 'hash' AND (
           jsonb_typeof(destination_data -> 'hash') <> 'string'
           OR btrim(destination_data ->> 'hash') = ''
       ))
       OR (destination_data ? 'compressionPending'
           AND jsonb_typeof(destination_data -> 'compressionPending') <> 'boolean')
    LIMIT 1;
    IF FOUND THEN
        RAISE EXCEPTION 'Cannot migrate publication_media row (publication_id=%, media_id=%) field %',
            invalid.publication_id, invalid.media_id, invalid.field;
    END IF;
END
$validation$;

UPDATE channels
SET initial_import_mode = coalesce(
    (source_data ->> 'initialImportMode')::initial_import_mode,
    'RECENT'::initial_import_mode
);

UPDATE channels
SET title = metadata ->> 'title',
    description_html = CASE
        WHEN metadata = '{}'::jsonb THEN NULL
        ELSE legacy_channel_description_html(metadata)
    END,
    public_username = metadata ->> 'publicUsername',
    emblem_remote_id = metadata #>> '{emblem,remoteId}',
    emblem_unique_id = metadata #>> '{emblem,uniqueId}',
    emblem_mime_type = metadata #>> '{emblem,mimeType}';

UPDATE messages
SET external_source_uri = source_data ->> 'externalSourceUri',
    forward_name = source_data #>> '{forwardAttribution,name}',
    forward_uri = source_data #>> '{forwardAttribution,url}';

UPDATE destinations
SET credential_ref = config ->> 'credentialRef',
    profile_sync = coalesce((config ->> 'profileSync')::boolean, true);

UPDATE destinations
SET profile_fingerprint = data #>> '{profile,fingerprint}',
    profile_source_emblem_id = data #>> '{profile,sourceEmblemUniqueId}',
    avatar_id = data #>> '{profile,avatarId}',
    avatar_media_id = data #>> '{profile,avatarMediaId}',
    avatar_shape = data #>> '{profile,avatarShape}';

UPDATE mirrors
SET feeds = ARRAY(
    SELECT feed.value
    FROM jsonb_array_elements_text(config -> 'feeds') WITH ORDINALITY AS feed(value, position)
    ORDER BY feed.position
);

UPDATE publications
SET destination_pinned = (destination_data ->> 'pinned')::boolean;

DELETE FROM publication_stories;

INSERT INTO publication_stories (publication_id, feed_name, story_id)
SELECT publications.id, story.key, story.value
FROM publications
CROSS JOIN LATERAL jsonb_each_text(
    coalesce(publications.destination_data -> 'storyIds', '{}'::jsonb)
) AS story(key, value);

UPDATE publication_media
SET destination_hash = destination_data ->> 'hash',
    compression_pending = coalesce((destination_data ->> 'compressionPending')::boolean, false);

UPDATE channels
SET source_data = source_data - ARRAY['initialImportMode', 'username', 'inviteLink'];

UPDATE messages
SET source_data = source_data - ARRAY['externalSourceUri', 'forwardAttribution'];

DROP FUNCTION legacy_channel_description_html(jsonb);
DROP FUNCTION legacy_utf16_boundary(text, integer);
DROP FUNCTION legacy_html_escape(text);
-- [jooq ignore stop]

ALTER TABLE destinations
    ALTER COLUMN credential_ref SET NOT NULL;

ALTER TABLE messages
    ADD CONSTRAINT messages_forward_uri_requires_name
        CHECK (forward_uri IS NULL OR forward_name IS NOT NULL);

ALTER TABLE channels
    DROP COLUMN metadata;

ALTER TABLE destinations
    DROP COLUMN config,
    DROP COLUMN data;

ALTER TABLE mirrors
    DROP COLUMN config;

ALTER TABLE publications
    DROP COLUMN destination_data;

ALTER TABLE publication_media
    DROP COLUMN destination_data;
