-- Clean model the agent queries. Every rule from the README and from data profiling lives
-- here as a column, so the LLM selects correct values instead of re-deriving them.
CREATE EXTENSION pg_trgm;

CREATE FUNCTION clean_num(t text) RETURNS numeric
  LANGUAGE sql IMMUTABLE PARALLEL SAFE
  AS $$ SELECT NULLIF(regexp_replace(t, '[[:space:]]', '', 'g'), '')::numeric $$;

CREATE FUNCTION ru_date(t text) RETURNS date
  LANGUAGE sql IMMUTABLE PARALLEL SAFE
  AS $$ SELECT to_date(NULLIF(trim(t), ''), 'DD.MM.YYYY') $$;

-- Company names: same key for Cyrillic and Latin spellings, apostrophes kept.
-- TO‘HER and TOHER are different companies in this data, so they must not collapse here.
CREATE FUNCTION spelled_key(t text) RETURNS text
  LANGUAGE sql IMMUTABLE PARALLEL SAFE
  AS $$
  SELECT trim(regexp_replace(regexp_replace(regexp_replace(
           translate(
             replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(
               translate(lower(t), '‘’ʻʼ`´', ''''''''''''),
               'ў', 'o'''), 'ғ', 'g'''), 'ё', 'yo'), 'ж', 'j'), 'ц', 'ts'), 'ч', 'ch'),
               'ш', 'sh'), 'щ', 'sh'), 'ю', 'yu'), 'я', 'ya'),
             'абвгдезийклмнопрстуфхыэқҳъь', 'abvgdeziyklmnoprstufxieqh'),
           '\m(mas''uliyati cheklangan jamiyati|mchj|ooo|xk|aj|oaj|qk|fx|ak)\M', ' ', 'g'),
           '[^a-z0-9'' ]', ' ', 'g'),
           '\s+', ' ', 'g'))
  $$;

CREATE FUNCTION loose_key(t text) RETURNS text
  LANGUAGE sql IMMUTABLE PARALLEL SAFE
  AS $$ SELECT replace(spelled_key(t), '''', '') $$;

-- Regions: deals and participants use Uzbek Latin, contract cards use Russian. Verified 1:1.
CREATE TABLE regions (region text PRIMARY KEY, region_ru text NOT NULL UNIQUE, region_uz_cyr text NOT NULL);
INSERT INTO regions VALUES
  ('Toshkent shahri', 'г. Ташкент', 'Тошкент шаҳри'),
  ('Toshkent viloyati', 'Ташкентская область', 'Тошкент вилояти'),
  ('Andijon viloyati', 'Андижанская область', 'Андижон вилояти'),
  ('Buxoro viloyati', 'Бухарская область', 'Бухоро вилояти'),
  ('Farg‘ona viloyati', 'Ферганская область', 'Фарғона вилояти'),
  ('Jizzax viloyati', 'Джизакская область', 'Жиззах вилояти'),
  ('Namangan viloyati', 'Наманганская область', 'Наманган вилояти'),
  ('Navoiy viloyati', 'Навоийская область', 'Навоий вилояти'),
  ('Qashqadaryo viloyati', 'Кашкадарьинская область', 'Қашқадарё вилояти'),
  ('Qoraqalpog‘iston Respublikasi', 'Республика Каракалпакстан', 'Қорақалпоғистон Республикаси'),
  ('Samarqand viloyati', 'Самаркандская область', 'Самарқанд вилояти'),
  ('Sirdaryo viloyati', 'Сырдарьинская область', 'Сирдарё вилояти'),
  ('Surxondaryo viloyati', 'Сурхандарьинская область', 'Сурхондарё вилояти'),
  ('Xorazm viloyati', 'Хорезмская область', 'Хоразм вилояти');

-- One product per row. 230 seller spellings collapse to these. HS code alone is not enough:
-- 271019 holds both diesel and mazut.
CREATE TABLE product_catalog (
  product text PRIMARY KEY, product_uz text NOT NULL, hs_code text NOT NULL, aliases text NOT NULL
);
INSERT INTO product_catalog VALUES
  ('Хлопок-сырец', 'Paxta xomashyosi', '520100', 'хлопок сырец, paxta'),
  ('Цемент портландский', 'Portlandsement', '252329', 'портландцемент, цемент, sement'),
  ('Пшеница продовольственная', 'Oziq-ovqat bug‘doyi', '100199', 'пшеница, bug‘doy'),
  ('Дизельное топливо', 'Dizel yoqilg‘isi', '271019', 'топливо дизельное, солярка, ДТ, dizel'),
  ('Мазут', 'Mazut', '271019', 'мазут топочный'),
  ('Мука', 'Un', '110100', 'мука пшеничная, bug‘doy uni'),
  ('Бензин автомобильный', 'Avtomobil benzini', '271012', 'бензин, АИ-80, АИ-92, АИ-95, benzin'),
  ('Полиэтилен', 'Polietilen', '390120', 'полиэтилен гранулированный'),
  ('Газ сжиженный', 'Suyultirilgan gaz', '271112', 'пропан-бутан, сжиженный углеводородный газ, СУГ, gaz'),
  ('Арматура стальная', 'Po‘lat armatura', '721420', 'арматура, armatura'),
  ('Сахар', 'Shakar', '170199', 'сахар белый, сахар-песок'),
  ('Масло хлопковое', 'Paxta yog‘i', '151229', 'хлопковое масло'),
  ('Карбамид', 'Karbamid', '310210', 'мочевина'),
  ('Полипропилен', 'Polipropilen', '390210', 'polipropilen'),
  ('Аммиачная селитра', 'Ammiakli selitra', '310230', 'селитра аммиачная, selitra'),
  ('Шрот хлопковый', 'Paxta shroti', '230610', 'шрот хлопковых семян, shrot'),
  ('Битум нефтяной', 'Neft bitumi', '271320', 'битум, bitum'),
  ('Поливинилхлорид', 'Polivinilxlorid', '390410', 'ПВХ'),
  ('Кирпич керамический', 'Keramik g‘isht', '690410', 'кирпич, g‘isht'),
  ('Кислота серная', 'Sulfat kislotasi', '280700', 'серная кислота'),
  ('Масло подсолнечное', 'Kungaboqar yog‘i', '151219', 'подсолнечное масло'),
  ('Катод медный', 'Mis katod', '740311', 'медный катод, медь, mis'),
  ('Пиломатериал обрезной', 'Kesilgan yog‘och', '440711', 'пиломатериалы, taxta'),
  ('Рис', 'Guruch', '100630', 'рис шлифованный'),
  ('Стекло листовое', 'Oyna', '700529', 'стекло флоат, стекло, shisha'),
  ('Лист стальной горячекатаный', 'Issiq prokatlangan po‘lat list', '720853', 'стальной лист'),
  ('Пряжа хлопчатобумажная', 'Paxta ipi', '520512', 'пряжа хлопковая, ip'),
  ('Комбикорм', 'Omuxta yem', '230990', 'кормосмесь, yem'),
  ('Соль пищевая', 'Osh tuzi', '250100', 'соль поваренная, соль, tuz'),
  ('Гипс строительный', 'Qurilish gipsi', '252020', 'гипс, gips'),
  ('Проволока арматурная', 'Armatura simi', '721710', 'проволока, sim'),
  ('Профиль алюминиевый', 'Alyuminiy profil', '760421', 'алюминиевый профиль'),
  ('Сода каустическая', 'Kaustik soda', '281511', 'soda'),
  ('Кабель силовой', 'Kuch kabeli', '854449', 'кабель, kabel'),
  ('Эмаль', 'Emal', '320810', 'эмаль алкидная, краска'),
  ('Известь', 'Ohak', '252210', 'известь негашеная, известь строительная'),
  ('Азот', 'Azot', '280430', 'azot'),
  ('Вода питьевая', 'Ichimlik suvi', '220190', 'питьевая вода, suv'),
  ('Подшипник', 'Podshipnik', '848210', 'podshipnik'),
  ('Трубы полиэтиленовые', 'Polietilen quvurlar', '391721', 'труба ПЭ, quvur'),
  ('Аргон газообразный', 'Argon', '280421', 'аргон');

CREATE TABLE participants AS
SELECT inn, name, region, activity, role, vat_payer = 'Да' AS vat_payer,
       spelled_key(name) AS spelled_key, loose_key(name) AS loose_key
FROM raw.participants;
ALTER TABLE participants ADD PRIMARY KEY (inn);
CREATE INDEX participants_loose_trgm ON participants USING gin (loose_key gin_trgm_ops);

CREATE TABLE brokers AS
SELECT code, name, inn, region, spelled_key(name) AS spelled_key, loose_key(name) AS loose_key
FROM raw.brokers;
ALTER TABLE brokers ADD PRIMARY KEY (code);

CREATE TABLE usd_rates AS
SELECT ru_date(rate_date) AS rate_date, clean_num(rate) AS rate FROM raw.usd_rates;
ALTER TABLE usd_rates ADD PRIMARY KEY (rate_date);

-- Contract cards with attributes normalised across seller spellings.
CREATE TEMP TABLE c0 AS
SELECT r.*,
       lower(concat_ws(' ', product_name, brand, size, grade, ptype, class)) AS full_text,
       CASE
         WHEN hs_code = '271019' AND (lower(product_name) LIKE '%мазут%' OR brand IN ('М-40', 'М-100'))
           THEN 'Мазут'
         ELSE (SELECT p.product FROM product_catalog p
               WHERE p.hs_code = r.hs_code AND p.product <> 'Мазут')
       END AS product,
       -- "А500С" arrives in the class column but is a steel brand.
       regexp_replace(upper(trim(CASE WHEN class = 'А500С' THEN 'А500С' WHEN brand = 'высший' THEN NULL ELSE brand END)),
                      '\s+Н$', 'Н') AS brand_n,
       regexp_replace(lower(size), '\s', '', 'g') AS size_n,
       lower(trim(ptype)) AS ptype_n
FROM raw.contracts r;

CREATE TEMP TABLE known_brand AS
  SELECT DISTINCT hs_code, brand_n AS v, regexp_replace(brand_n, '\s', '', 'g') AS k FROM c0 WHERE brand_n IS NOT NULL;
CREATE TEMP TABLE known_size AS
  SELECT DISTINCT hs_code, size_n AS v FROM c0 WHERE size_n IS NOT NULL;
CREATE TEMP TABLE known_ptype AS
  SELECT DISTINCT hs_code, ptype_n AS v FROM c0 WHERE ptype_n IS NOT NULL;

CREATE TABLE contracts AS
SELECT clean_num(c.contract_no)::bigint AS contract_no,
       c.product,
       cat.product_uz,
       c.product_name AS seller_product_name,
       COALESCE(c.brand_n, (SELECT b.v FROM known_brand b
                            WHERE b.hs_code = c.hs_code
                              AND position(b.k IN upper(regexp_replace(c.product_name, '\s', '', 'g'))) > 0
                            ORDER BY length(b.k) DESC LIMIT 1)) AS brand,
       COALESCE(c.size_n, (SELECT s.v FROM known_size s
                           WHERE s.hs_code = c.hs_code
                             AND position(s.v IN lower(regexp_replace(c.product_name, '\s', '', 'g'))) > 0
                           ORDER BY length(s.v) DESC LIMIT 1)) AS size,
       CASE
         WHEN c.full_text ~ 'в/с|высш' THEN 'высший'
         WHEN c.full_text ~ '(^|\s)(1|i|перв\S*)[- ]?сорт' THEN '1'
         WHEN c.full_text ~ '(^|\s)(2|ii|втор\S*)[- ]?сорт' THEN '2'
         WHEN c.full_text ~ '(^|\s)(3|iii|трет\S*)[- ]?сорт' THEN '3'
       END AS grade,
       CASE
         WHEN c.full_text ~ '(^|\s)(1|i)[- ]?класс' THEN '1'
         WHEN c.full_text ~ '(^|\s)(2|ii)[- ]?класс' THEN '2'
         WHEN c.full_text ~ '(^|\s)(3|iii)[- ]?класс' THEN '3'
         WHEN c.full_text ~ '(^|\s)(4|iv)[- ]?класс' THEN '4'
       END AS class,
       COALESCE(c.ptype_n, (SELECT t.v FROM known_ptype t
                            WHERE t.hs_code = c.hs_code
                              AND position(t.v IN lower(c.product_name)) > 0
                            ORDER BY length(t.v) DESC LIMIT 1)) AS ptype,
       c.hs_code, c.product_group, c.category, c.industry, c.unit,
       clean_num(c.lot) AS lot,
       CASE c.currency WHEN 'Доллар США' THEN 'USD' WHEN 'Узбекский сум' THEN 'UZS' END AS currency,
       clean_num(c.base_price) AS base_price,
       clean_num(replace(c.deposit, '%', '')) AS deposit_pct,
       c.delivery_basis, c.packaging,
       reg.region AS region,
       c.region AS region_ru,
       c.warehouse, c.auction_type, c.contract_type, c.contract_form,
       clean_num(c.payment_days)::int AS payment_days,
       clean_num(c.delivery_days)::int AS delivery_days,
       c.seller_inn,
       ru_date(c.registered_date) AS registered_date,
       ru_date(c.valid_until) AS valid_until
FROM c0 c
JOIN product_catalog cat ON cat.product = c.product
JOIN regions reg ON reg.region_ru = c.region;
ALTER TABLE contracts ADD PRIMARY KEY (contract_no);
CREATE INDEX contracts_product ON contracts (product);
CREATE INDEX contracts_seller ON contracts (seller_inn);

-- One wide row per deal with every derived value precomputed.
CREATE TABLE deals AS
WITH d AS (
  SELECT clean_num(deal_no)::bigint AS deal_no,
         ru_date(deal_date) AS deal_date,
         deal_time::time AS deal_time,
         auction_type,
         clean_num(quantity) AS quantity,
         clean_num(amount) AS amount,
         clean_num(start_price) AS start_price,
         clean_num(contract_no)::bigint AS contract_no,
         product_text,
         clean_num(lot) AS lot,
         unit, region, contract_type, contract_form, status,
         seller_inn, buyer_inn, seller_broker, buyer_broker,
         ru_date(paid_date) AS paid_date,
         ru_date(delivered_date) AS delivered_date,
         clean_num(delivered_qty) AS delivered_qty
  FROM raw.deals
)
SELECT d.deal_no, d.deal_date, d.deal_time,
       extract(year FROM d.deal_date)::int AS year,
       extract(quarter FROM d.deal_date)::int AS quarter,
       extract(month FROM d.deal_date)::int AS month,
       d.auction_type, d.contract_type, d.contract_form, d.status,
       d.status = 'Аннулирован(Не оплачен)' AS is_annulled,
       d.status IN ('Исполнен', 'Исполнен(Частично)') AS is_fulfilled,
       d.contract_no,
       c.product, c.product_uz, d.product_text, c.grade, c.class, c.brand, c.size, c.ptype,
       c.hs_code, c.product_group, c.category, c.industry,
       d.quantity, d.lot, d.unit,
       d.quantity * d.lot AS volume,
       CASE d.unit WHEN 'тонна' THEN d.quantity * d.lot
                   WHEN 'килограмм' THEN d.quantity * d.lot / 1000 END AS volume_tonnes,
       c.currency,
       d.amount,
       r.rate AS usd_rate,
       CASE c.currency WHEN 'USD' THEN d.amount * r.rate ELSE d.amount END AS amount_uzs,
       CASE c.currency WHEN 'USD' THEN d.amount ELSE d.amount / r.rate END AS amount_usd,
       d.amount / NULLIF(d.quantity * d.lot, 0) AS unit_price,
       d.start_price,
       c.base_price,
       d.region,
       reg.region_ru,
       d.seller_inn, s.name AS seller_name, s.activity AS seller_activity,
       d.buyer_inn, b.name AS buyer_name, b.region AS buyer_region, b.activity AS buyer_activity,
       d.seller_broker, sb.name AS seller_broker_name,
       d.buyer_broker, bb.name AS buyer_broker_name,
       d.paid_date, d.delivered_date, d.delivered_qty,
       d.delivered_qty * d.lot AS delivered_volume,
       c.payment_days, c.delivery_days,
       d.deal_date + c.payment_days AS payment_due,
       d.paid_date + c.delivery_days AS delivery_due,
       d.paid_date - d.deal_date AS days_to_pay,
       d.delivered_date - d.paid_date AS days_to_deliver,
       COALESCE(d.delivered_date > d.paid_date + c.delivery_days, false) AS delivered_late,
       (d.status = 'Оплачен' AND d.paid_date + c.delivery_days < DATE '2026-10-30') AS delivery_overdue
FROM d
JOIN contracts c ON c.contract_no = d.contract_no
JOIN regions reg ON reg.region = d.region
JOIN usd_rates r ON r.rate_date = d.deal_date
JOIN participants s ON s.inn = d.seller_inn
JOIN participants b ON b.inn = d.buyer_inn
JOIN brokers sb ON sb.code = d.seller_broker
JOIN brokers bb ON bb.code = d.buyer_broker;

ALTER TABLE deals ADD PRIMARY KEY (deal_no);
CREATE INDEX deals_date ON deals (deal_date);
CREATE INDEX deals_year_month ON deals (year, month);
CREATE INDEX deals_contract ON deals (contract_no);
CREATE INDEX deals_product ON deals (product);
CREATE INDEX deals_seller ON deals (seller_inn);
CREATE INDEX deals_buyer ON deals (buyer_inn);
CREATE INDEX deals_seller_broker ON deals (seller_broker);
CREATE INDEX deals_buyer_broker ON deals (buyer_broker);
CREATE INDEX deals_region ON deals (region);
CREATE INDEX deals_status ON deals (status);

-- One row per deal and broker. A broker on both sides (4,562 deals) appears once, with
-- broker_side = 'both', so totals by broker never double-count.
CREATE VIEW broker_deals AS
SELECT d.*, d.seller_broker AS broker_code, d.seller_broker_name AS broker_name,
       CASE WHEN d.seller_broker = d.buyer_broker THEN 'both' ELSE 'seller' END AS broker_side
FROM deals d
UNION ALL
SELECT d.*, d.buyer_broker, d.buyer_broker_name, 'buyer'
FROM deals d WHERE d.seller_broker <> d.buyer_broker;

ANALYZE;
