SELECT ACCLOG_REQUEST_URI, COUNT(*) AS ACCLOG_COUNT 
FROM DGB_ACCESS_LOG 
GROUP BY ACCLOG_REQUEST_URI 
ORDER BY ACCLOG_REQUEST_URI