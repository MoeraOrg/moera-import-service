-- [jooq ignore start]
WITH expanded_names AS (
    SELECT id,
           split_part(node_name, '_', 1) || '_' ||
           coalesce(nullif(split_part(node_name, '_', 2), ''), '0')::integer AS node_name
    FROM destinations
)
UPDATE destinations d
SET node_name = n.node_name,
    updated_at = now()
FROM expanded_names n
WHERE d.id = n.id
  AND d.node_name <> n.node_name;
-- [jooq ignore stop]
