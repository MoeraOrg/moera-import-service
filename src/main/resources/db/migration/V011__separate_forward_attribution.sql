-- Forward attribution is now rendered while composing the publication payload.
-- Remove only the exact prefix emitted by the previous Telegram mapper.
-- [jooq ignore start]
WITH escaped_attributions AS (
    SELECT m.id,
           replace(replace(replace(replace(replace(m.forward_name,
               '&', '&amp;'), '<', '&lt;'), '>', '&gt;'), '"', '&quot;'), '''', '&#39;') AS name,
           replace(replace(replace(replace(replace(m.forward_uri,
               '&', '&amp;'), '<', '&lt;'), '>', '&gt;'), '"', '&quot;'), '''', '&#39;') AS uri
    FROM messages m
    JOIN channels c ON c.id = m.channel_id
    WHERE c.source_type = 'TELEGRAM'
      AND m.body_format = 'HTML'
      AND m.forward_name IS NOT NULL
), prefixes AS (
    SELECT id,
           '<p>' || CASE WHEN uri IS NULL THEN name
                        ELSE '<a href="' || uri || '">' || name || '</a>' END || '</p>' AS prefix
    FROM escaped_attributions
), changed_messages AS (
    UPDATE messages m
    SET body = substring(m.body FROM length(p.prefix) + 1),
        revision = m.revision + 1,
        updated_at = now()
    FROM prefixes p
    WHERE m.id = p.id
      AND left(m.body, length(p.prefix)) = p.prefix
    RETURNING m.id
)
UPDATE publications
SET revision = revision + 1,
    attempts = 0,
    last_error = NULL,
    failure_type = NULL,
    failure_started_at = NULL,
    next_attempt_at = now(),
    updated_at = now()
WHERE id IN (
    SELECT pm.publication_id
    FROM publication_messages pm
    JOIN changed_messages m ON m.id = pm.message_id
);
-- [jooq ignore stop]
