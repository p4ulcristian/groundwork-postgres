SELECT order__id, order__total, order__at
FROM test_orders
WHERE order__shop_id = param('order/shop-id')
ORDER BY order__id
