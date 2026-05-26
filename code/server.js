const mariadb = require('mariadb');
const { spawn } = require("child_process");
const http = require("http");
const fs = require("fs");
const path = require("path");
const qs = require("querystring");
const formidable = require('formidable');

const pool = mariadb.createPool({
    host: '127.0.0.1',
    user: 'web_user',
    password: 'web_123',
    database: 'my_system',
    connectionLimit: 10
});

const port = 3000;
const ip = "0.0.0.0";

const sendResponse = (filename, statusCode, response) => {
    fs.readFile(`./html/${filename}`, (error, data) => {
        if (error) {
            response.statusCode = 500;
            response.setHeader("Content-Type", "text/plain");
            response.end("Sorry internal error");
        } else {
            response.statusCode = statusCode;
            response.setHeader("Content-Type", "text/html; charset=utf-8");
            response.end(data);
        }
    });
};

const server = http.createServer(async (request, response) => {
    const method = request.method;
    const requestUrl = new URL(request.url, `http://${request.headers.host}`);
    const pathname = requestUrl.pathname;

    if (method === "GET") {
        const lang = requestUrl.searchParams.get("lang");
        let selector = (lang === "en") ? "" : "-zh";

        if (pathname === "/") {
            return sendResponse(`index${selector}.html`, 200, response);
        }

        let cleanName = pathname.replace(".html", "").replace("-zh", "").slice(1);
        const finalFilename = `${cleanName}${selector}.html`;
        const validPages = ["index", "about", "login", "login-success", "login-fail", "register"];

        if (validPages.includes(cleanName)) {
            sendResponse(finalFilename, 200, response);
        } else {
            sendResponse(`404${selector}.html`, 404, response);
        }
    } 
    else if (method === "POST") {
        const currentPath = pathname;
        const lang = requestUrl.searchParams.get("lang");
        const redirectSelector = (lang === "en") ? "" : "-zh";

        // ==================== 分支 1：登入與註冊 ====================
        if (currentPath === "/process-login" || currentPath === "/process-register") {
            let body = [];
            request.on("data", (chunk) => body.push(chunk));
            request.on("end", async () => {
                const parsedBody = qs.parse(Buffer.concat(body).toString());
                const { username, password } = parsedBody;
                response.setHeader("Content-Type", "text/html; charset=utf-8");

                if (currentPath === "/process-login") {
                    let conn;
                    try {
                        conn = await pool.getConnection();
                        const rows = await conn.query("SELECT * FROM users WHERE username = ? AND password = ?", [username, password]);
                        if (rows.length > 0) {
                            response.statusCode = 302;
                            response.setHeader("Location", `/login-success${redirectSelector}.html?user=${encodeURIComponent(username)}`);
                            response.end();
                        } else {
                            response.statusCode = 302;
                            response.setHeader("Location", `/login-fail${redirectSelector}.html`);
                            response.end();
                        }
                    } catch (err) {
                        console.error("登入錯誤:", err);
                        response.end("<h1>伺服器忙碌中</h1>");
                    } finally {
                        if (conn) conn.release();
                    }
                } 
                else if (currentPath === "/process-register") {
                    let conn;
                    try {
                        conn = await pool.getConnection();
                        const existing = await conn.query("SELECT * FROM users WHERE username = ?", [username]);
                        if (existing.length > 0) {
                            response.end("<h1>帳號已存在</h1><a href='/register.html'>返回重試</a>");
                        } else {
                            await conn.query("INSERT INTO users (username, password) VALUES (?, ?)", [username, password]);
                            response.end("<h1>註冊成功</h1><a href='/login.html'>前往登入</a>");
                        }
                    } catch (err) {
                        response.end("<h1>註冊系統錯誤</h1>");
                    } finally {
                        if (conn) conn.release();
                    }
                }
            });
        }
        // ==================== 分支 2：查詢歷史紀錄  ====================
        else if (currentPath === "/get-records") {
            let body = [];
            request.on("data", (chunk) => body.push(chunk));
            request.on("end", async () => {
                try {
                    const parsedBody = qs.parse(Buffer.concat(body).toString());
                    const { username } = parsedBody;

                    response.setHeader("Content-Type", "application/json; charset=utf-8");

                    if (!username) {
                        response.statusCode = 400;
                        return response.end(JSON.stringify({ success: false, message: "缺少使用者名稱" }));
                    }

                    let conn;
                    try {
                        conn = await pool.getConnection();
                        const rows = await conn.query(
                            "SELECT filename, result, created_at FROM records WHERE username = ? ORDER BY created_at DESC", 
                            [username]
                        );
                        
                        response.statusCode = 200;
                        response.end(JSON.stringify({ success: true, data: rows }));
                    } catch (dbErr) {
                        console.error("資料庫查詢失敗:", dbErr);
                        response.statusCode = 500;
                        return response.end(JSON.stringify({ success: false, message: "資料庫查詢失敗" }));
                    } finally {
                        if (conn) conn.release();
                    }
                } catch (e) {
                    response.statusCode = 500;
                    return response.end(JSON.stringify({ success: false, message: "伺服器內部錯誤" }));
                }
            });
        }
        // ==================== 分支 3：上傳音檔 ====================
        else if (currentPath === "/upload-audio") {
            const form = new formidable.IncomingForm();
            form.uploadDir = path.join(__dirname, "upload");
            form.keepExtensions = true;

            form.parse(request, async (err, fields, files) => {
                response.setHeader("Content-Type", "text/html; charset=utf-8");
                if (err) return response.end("<h1>解析失敗</h1>");

                let username = "";
                if (fields.username) {
                    const raw = Array.isArray(fields.username) ? fields.username[0] : fields.username;
                    username = String(raw || "").trim();
                }

                console.log(`[DEBUG] 收到上傳請求，帳號為: "${username}"`);

                if (!username || username === "undefined") {
                    console.error("!!! 攔截到錯誤帳號:", username);
                    return response.end(`<h1>錯誤：無法識別使用者身份</h1><p>請重新登入</p>`);
                }

                try {
                    const audioFile = Array.isArray(files.audio) ? files.audio[0] : files.audio;
                    if (!audioFile) return response.end("<h1>找不到音檔</h1>");

                    const oldPath = audioFile.filepath;
                    const customFileName = `${username}_${Date.now()}.wav`;
                    const newPath = path.join(__dirname, "upload", customFileName);

                    fs.renameSync(oldPath, newPath);

                    const pythonProcess = spawn('python', ["pretice.py", newPath], {
                        env: { ...process.env, PYTHONIOENCODING: 'utf-8' }
                    });

                    let recognition_text = "";
                    pythonProcess.stdout.on('data', (data) => { recognition_text += data.toString('utf8'); });

                    pythonProcess.on('close', async (code) => {
                        let conn;
                        try {
                            conn = await pool.getConnection();
                            const userCheck = await conn.query("SELECT username FROM users WHERE username = ?", [username]);
                            
                            if (userCheck.length === 0) {
                                console.log(`[警告] 帳號 "${username}" 不在資料庫中！`);
                                return response.end(`
                                <h1>存檔失敗</h1>
                                <p>原因：帳號 "${username}" 不在資料庫名單中。</p>
                                <br>
                                <a href="/login-success${redirectSelector}.html?user=${encodeURIComponent(username)}" style="padding: 10px 20px; background-color: #f44336; color: white; text-decoration: none; border-radius: 4px;">重新上傳</a>
                                `);
                            }

                            await conn.query(
                                "INSERT INTO records (username, filename, result) VALUES (?, ?, ?)",
                                [username, customFileName, recognition_text.trim() || "無辨識結果"]
                            );
                            
                            response.end(`
                                <!DOCTYPE html>
                                <html lang="zh-TW">
                                <head>
                                    <meta charset="UTF-8">
                                    <title>辨識成功</title>
                                    <style>
                                        body { font-family: sans-serif; padding: 20px; }
                                        table { width: 100%; border-collapse: collapse; margin-top: 20px; }
                                        th, td { border: 1px solid #ddd; padding: 8px; text-align: left; }
                                        th { background-color: #f2f2f2; }
                                        .history-section { margin-top: 40px; }
                                        .btn { padding: 10px 20px; color: white; text-decoration: none; border-radius: 4px; display: inline-block; cursor: pointer; border: none; font-size: 14px; }
                                        .btn-success { background-color: #4CAF50; margin-right: 10px; }
                                        .btn-primary { background-color: #008CBA; }
                                    </style>
                                </head>
                                <body>
                                    <h1>辨識成功</h1>
                                    <p>帳號：${username}</p>
                                    <div style="background: #f0f0f0; padding: 15px; margin: 10px 0;">${recognition_text}</div>
                                    <br>
        
                                    <a href="/login-success${redirectSelector}.html?user=${encodeURIComponent(username)}" class="btn btn-success">繼續上傳</a>
                                    <a href='/' style="margin-right: 15px;">返回首頁</a>
                            
                                    <div class="history-section">
                                        <hr>
                                        <h2>歷史辨識紀錄</h2>
                                        <input type="hidden" id="usernameHide" value="${username}">
                                        <button type="button" class="btn btn-primary" onclick="fetchHistoryFromServer()">檢視歷史紀錄</button>
            
                                        <div id="historyContainer" style="display: none;">
                                            <table>
                                                <thead>
                                                    <tr>
                                                        <th>上傳時間</th>
                                                        <th>檔案名稱</th>
                                                        <th>辨識結果</th>
                                                    </tr>
                                                </thead>
                                                <tbody id="historyTableBody">
                                                    </tbody>
                                            </table>
                                        </div>
                                    </div>

                                    <script>
                                        async function fetchHistoryFromServer() {
                                            const username = document.getElementById('usernameHide').value;
                                            if (!username) return alert("無法查詢，帳號資訊遺失");

                                            try {
                                                
                                                const response = await fetch('/get-records', {
                                                    method: 'POST',
                                                    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
                                                    body: 'username=' + encodeURIComponent(username)
                                                });

                                                const result = await response.json();
                    
                                                if (result.success) {
                                                    const tableBody = document.getElementById('historyTableBody');
                                                    tableBody.innerHTML = ''; 
                            
                                                    if (result.data.length === 0) {
                                                        tableBody.innerHTML = '<tr><td colspan="3">目前尚無上傳紀錄</td></tr>';
                                                    } else {
                                                        result.data.forEach(row => {
                                                            const tr = document.createElement('tr');
                                                            const date = new Date(row.created_at).toLocaleString('zh-TW');
                                                            tr.innerHTML = '<td>' + date + '</td><td>' + row.filename + '</td><td>' + row.result + '</td>';
                                                            tableBody.appendChild(tr);
                                                        });
                                                    }

                                                    document.getElementById('historyContainer').style.display = 'block';
                                                } else {
                                                    alert("查詢失敗：" + result.message);
                                                }
                                            } catch (error) {
                                                console.error("發送查詢請求失敗:", error);
                                                alert("連線失敗，無法取得歷史紀錄");
                                            }
                                        }
                                    </script>
                                </body>
                                </html>
                            `);
                        } catch (dbErr) {
                            console.error("SQL 報錯:", dbErr);
                            response.end(`<h1>資料庫錯誤</h1><p>${dbErr.message}</p>`);
                        } finally {
                            if (conn) conn.release();
                        }
                    });

                } catch (e) {
                    console.error("系統錯誤:", e);
                    response.end("<h1>伺服器執行錯誤</h1>");
                }
            });
        }
    }
});

server.listen(port, ip, () => {
    console.log(`Server is running at http://${ip}:${port}`);
});
