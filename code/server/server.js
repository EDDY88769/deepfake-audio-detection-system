require('dotenv').config(); 
const mariadb = require('mariadb');
const { spawn } = require("child_process");
const http = require("http");
const fs = require("fs");
const path = require("path");
const qs = require("querystring");
const formidable = require('formidable');
const bcrypt = require('bcrypt'); 

const pool = mariadb.createPool({
    host: process.env.DB_HOST || '127.0.0.1',
    user: process.env.DB_USER || 'web_user',
    password: process.env.DB_PASSWORD, 
    database: process.env.DB_NAME || 'my_system',
    connectionLimit: 10
});

const port = 3000;
const ip = "0.0.0.0"; // 💡 優化：改為 0.0.0.0 配合 ngrok 與內網測試

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

        // ------ 💡 新增：前端線上試聽靜態音訊路由 ------
        if (pathname.startsWith('/view-audio/')) {
            const filename = path.basename(pathname);
            const filePath = path.join(__dirname, 'upload', filename);

            if (fs.existsSync(filePath)) {
                response.writeHead(200, { 'Content-Type': 'audio/wav' });
                return fs.createReadStream(filePath).pipe(response);
            } else {
                response.writeHead(404, { 'Content-Type': 'text/plain' });
                return response.end('File Not Found');
            }
        }

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
                        const rows = await conn.query("SELECT * FROM users WHERE username = ?", [username]);
                        
                        if (rows.length > 0) {
                            const dbUser = rows[0];
                            const isMatch = await bcrypt.compare(password, dbUser.password);

                            if (isMatch) {
                                response.statusCode = 302;
                                response.setHeader("Location", `/login-success${redirectSelector}.html?user=${encodeURIComponent(username)}`);
                                response.end();
                            } else {
                                response.statusCode = 302;
                                response.setHeader("Location", `/login-fail${redirectSelector}.html`);
                                response.end();
                            }
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
                            const saltRounds = 10;
                            const hashedPassword = await bcrypt.hash(password, saltRounds);
                            await conn.query("INSERT INTO users (username, password) VALUES (?, ?)", [username, hashedPassword]);
                            response.end("<h1>註冊成功</h1><a href='/login.html'>前往登入</a>");
                        }
                    } catch (err) {
                        console.error("註冊錯誤:", err);
                        response.end("<h1>註冊系統錯誤</h1>");
                    } finally {
                        if (conn) conn.release();
                    }
                }
            });
        }
        // ==================== 分支 2：查詢歷史紀錄 ====================
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
                        // 💡 查詢優化：撈出 original_filename 讓前端顯示
                        const rows = await conn.query(
                            "SELECT filename, original_filename, result, created_at FROM records WHERE username = ?", 
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
        // ==================== 分支 3：上傳音檔（Bug 修正與結構理順） ====================
        else if (currentPath === "/upload-audio") {
            const form = new formidable.IncomingForm();
            const uploadDir = path.join(__dirname, "upload");
            form.uploadDir = uploadDir;
            form.keepExtensions = true;

            form.parse(request, async (err, fields, files) => {
                response.setHeader("Content-Type", "text/html; charset=utf-8");
                if (err) return response.end("<h1>解析失敗</h1>");

                let username = "";
                if (fields.username) {
                    const raw = Array.isArray(fields.username) ? fields.username[0] : fields.username;
                    username = String(raw || "").trim();
                }

                if (!username || username === "undefined") {
                    return response.end(`<h1>錯誤：無法識別使用者身份</h1><p>請重新登入</p>`);
                }

                try {
                    const audioFile = Array.isArray(files.audio) ? files.audio[0] : files.audio;
                    if (!audioFile) return response.end("<h1>找不到音檔</h1>");

                    // 1. 抓取前端原始檔名與生成安全檔名
                    const originalName = audioFile.originalFilename || "unknown.wav"; 
                    const customFileName = `${username}_${Date.now()}.wav`;
                    const newPath = path.join(uploadDir, customFileName);

                    // 2. 搬移實體檔案
                    fs.renameSync(audioFile.filepath, newPath);

                    // 3. 呼叫 Python 子程序進行 AI 辨識
                    const pythonProcess = spawn('python', ["pretice.py", newPath], {
                        env: { ...process.env, PYTHONIOENCODING: 'utf-8' }
                    });

                    let recognition_text = "";
                    pythonProcess.stdout.on('data', (data) => { recognition_text += data.toString('utf8'); });

                    // 4. 當 Python 辨識結束後，再執行寫入資料庫與回傳網頁
                    pythonProcess.on('close', async (code) => {
                        let conn;
                        try {
                            conn = await pool.getConnection();
                            const userCheck = await conn.query("SELECT username FROM users WHERE username = ?", [username]);

                            if (userCheck.length === 0) {
                                return response.end(`<h1>存檔失敗</h1><p>原因：帳號 "${username}" 不在資料庫中。</p>`);
                            }

                            // 5. 🔥 關鍵：同步將安全檔名、原始檔名、辨識結果寫入 records 資料表
                            await conn.query(
                                "INSERT INTO records (username, filename, original_filename, result) VALUES (?, ?, ?, ?)",
                                [username, customFileName, originalName, recognition_text.trim() || "無辨識結果"]
                            );

                            // 6. 渲染成功的 HTML 畫面給前端，內附前端歷史紀錄與試聽功能
                            response.end(`
                                <!DOCTYPE html>
                                <html lang="zh-TW">
                                <head>
                                    <meta charset="UTF-8">
                                    <title>辨識成功</title>
                                    <style>
                                        body { font-family: sans-serif; padding: 20px; background: #fafafa; }
                                        table { width: 100%; border-collapse: collapse; margin-top: 20px; background: white; }
                                        th, td { border: 1px solid #ddd; padding: 12px; text-align: left; }
                                        th { background-color: #f2f2f2; }
                                        .history-section { margin-top: 40px; }
                                        .btn { padding: 10px 20px; color: white; text-decoration: none; border-radius: 4px; display: inline-block; cursor: pointer; border: none; font-size: 14px; }
                                        .btn-success { background-color: #4CAF50; margin-right: 10px; }
                                        .btn-primary { background-color: #008CBA; }
                                        audio { width: 100%; max-width: 250px; }
                                    </style>
                                </head>
                                <body>
                                    <h1>🎉 辨識成功</h1>
                                    <p>操作帳號：<strong>${username}</strong></p>
                                    <div style="background: #eef9ff; border-left: 5px solid #008CBA; padding: 15px; margin: 10px 0;">
                                        <strong>AI 偵測輸出結果：</strong><br>${recognition_text}
                                    </div>
                                    <br>
                                    <a href="/login-success${redirectSelector}.html?user=${encodeURIComponent(username)}" class="btn btn-success">繼續上傳</a>
                                    <a href='/'>返回首頁</a>
                                    
                                    <div class="history-section">
                                        <hr>
                                        <h2>📊 您的歷史辨識紀錄</h2>
                                        <input type="hidden" id="usernameHide" value="${username}">
                                        <button type="button" class="btn btn-primary" onclick="fetchHistoryFromServer()">點此整理並檢視歷史紀錄</button>
                            
                                        <div id="historyContainer" style="display: none;">
                                            <table>
                                                <thead>
                                                    <tr>
                                                        <th>上傳時間</th>
                                                        <th>原始檔案名稱</th>
                                                        <th>辨識結果</th>
                                                        <th>線上試聽</th>
                                                    </tr>
                                                </thead>
                                                <tbody id="historyTableBody"></tbody>
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
                                                        tableBody.innerHTML = '<tr><td colspan="4">目前尚無上傳紀錄</td></tr>';
                                                    } else {
                                                        result.data.forEach(row => {
                                                            const tr = document.createElement('tr');
                                                            const date = new Date(row.created_at).toLocaleString('zh-TW');
                                                            
                                                            // 💡 關鍵優化：畫面上秀出 original_filename，但 <audio> 標籤呼叫安全的 filename
                                                            tr.innerHTML = \`
                                                                <td>\${date}</td>
                                                                <td>\${row.original_filename || "未命名音檔"}</td>
                                                                <td>\${row.result}</td>
                                                                <td>
                                                                    <audio controls src="/view-audio/\${row.filename}" preload="none"></audio>
                                                                </td>
                                                            \`;
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
