-- =============================================================================
-- V21: Backfill users.email to its canonical (trimmed + lowercased) form.
--
-- The auth surface (signup, login, forgot/reset password, brand-partner
-- verification/resend, Google Sign-In) now routes every email through
-- EmailNormalizer.normalize() -- trim + lowercase -- before any lookup or write.
-- users.email is `varchar(255) unique` and Postgres compares it case-sensitively,
-- so any pre-existing row whose stored email is NOT already trim+lowercase became
-- unreachable the moment lookups started normalizing: findByEmail('john@x.com')
-- never matches the stored 'John@X.com'. Worse, existsByEmail() on the normalized
-- form also returns false, so re-signup would silently mint a SECOND row for the
-- same person. This one-off aligns the stored data with the new canonical form.
--
-- Step 1 is a hard-stop guard. Collapsing two rows onto the same normalized email
-- cannot happen under today's case-sensitive unique constraint being violated --
-- but 'John@X.com' and 'john@x.com' CAN legitimately coexist as two distinct rows
-- right now, and the UPDATE below would then violate the unique index mid-migration.
-- Rather than let it fail with an opaque constraint error (or, worse, silently skip
-- or merge), we detect the case up front and abort with an actionable message.
-- Merging two accounts decides who keeps the orders, customer profile, addresses,
-- brand-partner record and OAuth links -- a business decision no migration may make
-- on its own. An operator must reconcile the duplicates manually, then re-run.
--
-- Step 2 is a no-op for every row that is already normalized (the WHERE filters
-- them out), so this migration is effectively idempotent in content terms.
--
-- Additive/corrective only; V0.0.1..V20 untouched (Flyway checksum).
-- =============================================================================

-- Step 1: abort if normalization would collide two existing rows onto one email.
DO $$
DECLARE
    collision_count integer;
    sample_email    text;
BEGIN
    SELECT count(*), min(normalized_email)
      INTO collision_count, sample_email
      FROM (
            SELECT lower(trim(email)) AS normalized_email
              FROM users
             GROUP BY lower(trim(email))
            HAVING count(*) > 1
           ) AS collisions;

    IF collision_count > 0 THEN
        RAISE EXCEPTION
            'V21 aborted: % email(s) in users would collide when normalized to lower(trim(email)) (e.g. %). '
            'Two or more accounts differ only by case/whitespace. Reconcile them manually -- decide which row '
            'keeps its orders, customer profile, addresses and OAuth links, remove or re-key the others -- '
            'then re-run this migration.',
            collision_count, sample_email;
    END IF;
END $$;

-- Step 2: backfill. Rows already in canonical form are skipped by the WHERE clause.
UPDATE users
   SET email = lower(trim(email))
 WHERE email <> lower(trim(email));
