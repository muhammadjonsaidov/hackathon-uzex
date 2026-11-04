-- Raw CSVs, loaded as text exactly as published. Runs once, on an empty data volume.
CREATE SCHEMA raw;

CREATE TABLE raw.deals (
  deal_no text, deal_date text, deal_time text, auction_type text, quantity text, amount text,
  start_price text, contract_no text, product_text text, lot text, unit text, region text,
  contract_type text, contract_form text, status text, seller_inn text, buyer_inn text,
  seller_broker text, buyer_broker text, paid_date text, delivered_date text, delivered_qty text
);
CREATE TABLE raw.contracts (
  contract_no text, product_name text, brand text, size text, grade text, ptype text, class text,
  hs_code text, product_group text, category text, industry text, unit text, lot text,
  currency text, base_price text, deposit text, delivery_basis text, region text, warehouse text,
  auction_type text, contract_type text, contract_form text, payment_days text, delivery_days text,
  packaging text, seller_inn text, registered_date text, valid_until text
);
CREATE TABLE raw.participants (
  inn text, name text, region text, activity text, role text, vat_payer text
);
CREATE TABLE raw.brokers (code text, name text, inn text, region text);
CREATE TABLE raw.usd_rates (rate_date text, rate text);

COPY raw.deals FROM '/data/deals_2022.csv' WITH (FORMAT csv, HEADER true);
COPY raw.deals FROM '/data/deals_2023.csv' WITH (FORMAT csv, HEADER true);
COPY raw.deals FROM '/data/deals_2024.csv' WITH (FORMAT csv, HEADER true);
COPY raw.deals FROM '/data/deals_2025.csv' WITH (FORMAT csv, HEADER true);
COPY raw.deals FROM '/data/deals_2026.csv' WITH (FORMAT csv, HEADER true);
COPY raw.contracts FROM '/data/contracts.csv' WITH (FORMAT csv, HEADER true);
COPY raw.participants FROM '/data/participants.csv' WITH (FORMAT csv, HEADER true);
COPY raw.brokers FROM '/data/brokers.csv' WITH (FORMAT csv, HEADER true);
COPY raw.usd_rates FROM '/data/usd_rates.csv' WITH (FORMAT csv, HEADER true);
