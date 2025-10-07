#!/bin/bash

# Manual Google Photos API Test Script
# This will help us diagnose the exact API issue

echo "🔍 Google Photos API Manual Test"
echo "=================================="

# Extract a token from the app logs
echo "1. First, get a fresh token from the app logs"
echo "2. Replace TOKEN_HERE with the actual token from logs"
echo "3. Run this script to test the API directly"

TOKEN="TOKEN_HERE"  # Replace with actual token from logs

if [ "$TOKEN" = "TOKEN_HERE" ]; then
    echo "❌ Please replace TOKEN_HERE with an actual token from the app logs"
    echo "   Look for lines like: '🔑 Token (first 20 chars): ya29.a0AQQ_BDRVur62P...'"
    echo "   You'll need to combine the first 20 and last 20 characters"
    exit 1
fi

echo ""
echo "🔍 Testing token info..."
curl -s "https://www.googleapis.com/oauth2/v1/tokeninfo?access_token=$TOKEN" | jq '.' || echo "❌ jq not available, raw response above"

echo ""
echo "🔍 Testing Google Photos API directly..."
echo "Making request to: https://photoslibrary.googleapis.com/v1/albums?pageSize=1"

response=$(curl -s -w "\nHTTP_STATUS:%{http_code}" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  "https://photoslibrary.googleapis.com/v1/albums?pageSize=1")

echo "Response:"
echo "$response"

http_status=$(echo "$response" | grep "HTTP_STATUS:" | cut -d: -f2)

echo ""
echo "📊 Results Analysis:"
echo "HTTP Status: $http_status"

case $http_status in
    200)
        echo "✅ SUCCESS: API call worked! The issue might be app-specific."
        ;;
    403)
        echo "❌ 403 FORBIDDEN: Confirms the API access issue"
        echo "   This suggests Google Cloud Console API configuration problem"
        ;;
    401)
        echo "❌ 401 UNAUTHORIZED: Token issue"
        ;;
    404)
        echo "❌ 404 NOT FOUND: API endpoint not available"
        ;;
    *)
        echo "❓ Unexpected status code: $http_status"
        ;;
esac

echo ""
echo "🔧 Next Steps Based on Results:"
echo "- If 200: The issue is in the app's HTTP request formatting"
echo "- If 403: Google Cloud Console API not properly enabled/configured"  
echo "- If 401: OAuth token issue (but this seems unlikely based on token info)"
echo "- If 404: Google Photos Library API not available in your region/project"

echo ""
echo "🌐 Manual Google Cloud Console Checks:"
echo "1. Go to: https://console.cloud.google.com/apis/api/photoslibrary.googleapis.com"
echo "2. Ensure 'Google Photos Library API' shows as 'ENABLED'"
echo "3. Check quotas: https://console.cloud.google.com/apis/api/photoslibrary.googleapis.com/quotas"
echo "4. Verify your project has billing enabled (required for some Google APIs)"