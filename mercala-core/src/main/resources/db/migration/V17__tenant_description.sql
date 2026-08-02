-- Public store profile: what the store sells, shown on its storefront page.
-- Nullable — existing tenants simply have no description yet.
ALTER TABLE tenant ADD COLUMN description TEXT;
