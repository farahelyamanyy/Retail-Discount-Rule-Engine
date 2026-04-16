CREATE TABLE  orders_processed (
  transaction_date TIMESTAMP PRIMARY KEY,
  product_name VARCHAR2(100),
  expiry_date DATE,
  quantity NUMBER,
  unit_price NUMBER,
  channel VARCHAR2(50),
  payment_method VARCHAR2(50),
  original_price NUMBER,
  discount NUMBER,
  final_price NUMBER
);