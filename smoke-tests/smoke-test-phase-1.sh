docker-compose up -d
mvn spring-boot:run

# Create an order
curl -X POST http://localhost:8080/api/v1/orders \
  -H "Content-Type: application/json" \
  -d '{
    "customerId": "CUST-001",
    "items": [{"productId": "PROD-001", "quantity": 2}],
    "paymentMethod": "CREDIT_CARD",
    "shippingAddress": {
      "street": "123 Main St", "city": "San Francisco",
      "state": "CA", "postalCode": "94102", "country": "US"
    }
  }'

# Fetch it back using the orderId from the response
curl http://localhost:8080/api/v1/orders/{orderId}