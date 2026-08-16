-- =============================================================================
-- V25: S3 media storage keys — hard cutover (key-only, presigned PUT + CDN read)
--
-- product_images/product_videos are confirmed EMPTY (product owner). Hard cutover: DELETE before
-- adding the NOT NULL columns is what makes that legal without a default. No dual-read path —
-- storage_key is the single source from this migration forward. brand_partners.logo_url is
-- likewise dropped; hero is net-new and key-only from birth (no legacy hero_image_url ever existed).
-- =============================================================================

DELETE FROM product_images;
DELETE FROM product_videos;

ALTER TABLE product_images DROP COLUMN image_url;
ALTER TABLE product_images ADD COLUMN storage_key varchar(512) NOT NULL;

ALTER TABLE product_videos DROP COLUMN video_url;
ALTER TABLE product_videos DROP COLUMN thumbnail_url;
ALTER TABLE product_videos ADD COLUMN storage_key varchar(512) NOT NULL;
ALTER TABLE product_videos ADD COLUMN thumbnail_storage_key varchar(512);

ALTER TABLE brand_partners DROP COLUMN logo_url;
ALTER TABLE brand_partners ADD COLUMN logo_storage_key varchar(512);
ALTER TABLE brand_partners ADD COLUMN hero_storage_key varchar(512);
