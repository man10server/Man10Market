package red.man10.man10market

import org.bukkit.plugin.java.JavaPlugin
import red.man10.man10bank.BankAPI
import red.man10.man10bank.service.VaultManager
import red.man10.man10itembank.util.MySQLManager
import red.man10.man10market.assistant.Assistant
import red.man10.man10market.assistant.AssistantConfig
import red.man10.man10market.assistant.ConversationManager
import red.man10.man10market.map.MappRenderer
import red.man10.man10market.stock.Stock
import java.io.BufferedReader
import java.io.InputStreamReader

class Man10Market : JavaPlugin() {

    companion object {
        lateinit var instance: Man10Market
        lateinit var bankAPI: BankAPI
        lateinit var vault: VaultManager

        var isMarketOpen = false
        var csvPath = ""

        fun setupAssistant() {
            try {
                // アシスタントの初期化
                instance.logger.info("アシスタント機能を初期化中...")
                
                // 会話マネージャーの初期化
                ConversationManager.setup(instance)
                
                // アシスタントの初期化（カスタム設定を使用）
                val assistantConfig = AssistantConfig(
                    apiKey = instance.config.getString("Assistant.ApiKey","")?:"",
                    model = instance.config.getString("Assistant.Model", "gpt-4o")?:"gpt-4o",
                    temperature = instance.config.getDouble("Assistant.Temperature", 0.7),
                    maxTokens = instance.config.getInt("Assistant.MaxTokens", 2048)
                )
                Assistant.setup(instance, assistantConfig)
                
                instance.logger.info("アシスタント機能が正常に初期化されました")
            } catch (e: Exception) {
                instance.logger.severe("アシスタント機能の初期化中にエラーが発生しました: ${e.message}")
                e.printStackTrace()
            }
        }
    }


    override fun onEnable() {
        // Plugin startup logic

        saveDefaultConfig()

        instance = this
        bankAPI = BankAPI(this)
        vault = VaultManager(this)

        getCommand("mce")!!.setExecutor(Command)
        getCommand("mstock")!!.setExecutor(Stock)

        // テーブル初期化
        initializeTables()
        
        MappRenderer.setup(this)
        loadMarketConfig()
        MarketData.init()

        setupAssistant()
    }

    override fun onDisable() {
        // Plugin shutdown logic
        Market.interruptTransactionQueue()
    }

    fun loadMarketConfig() {
        reloadConfig()

        isMarketOpen = config.getBoolean("MarketOpen", false)
        csvPath = config.getString("CSVPath","")?:""
        
        // アシスタント設定の読み込み
        val assistantModel = config.getString("Assistant.Model", "gpt-4o")?:"gpt-4o"
        val assistantTemperature = config.getDouble("Assistant.Temperature", 0.7)
        val assistantMaxTokens = config.getInt("Assistant.MaxTokens", 2048)
        
        // デフォルト設定をconfig.ymlに保存
        if (!config.contains("Assistant.Model")) {
            config.set("Assistant.Model", assistantModel)
            config.set("Assistant.Temperature", assistantTemperature)
            config.set("Assistant.MaxTokens", assistantMaxTokens)
            saveConfig()
        }
    }
    
    /**
     * SQLテーブルを初期化する
     */
    private fun initializeTables() {
        logger.info("テーブルの初期化を開始します...")
        
        try {
            // SQLファイルを読み込む
            val inputStream = getResource("table.sql")
            if (inputStream == null) {
                logger.warning("table.sqlファイルが見つかりません")
                return
            }
            
            val reader = BufferedReader(InputStreamReader(inputStream))
            val sqlBuilder = StringBuilder()
            var line: String?

            val mysql = MySQLManager(this, "Man10MarketInit")
            
            // SQLファイルの内容を読み込む
            while (reader.readLine().also { line = it } != null) {
                // コメント行スキップ
                if (line!!.trim().startsWith("--")) {
                    continue
                }
                sqlBuilder.append(line).append("\n")
                
                // SQLステートメントの終わりを検出したら実行
                if (line!!.trim().endsWith(";")) {
                    val sql = sqlBuilder.toString().trim()
                    if (sql.isNotEmpty()) {
                        mysql.execute(sql)
                    }
                    sqlBuilder.clear()
                }
            }
            
            reader.close()
            logger.info("テーブルの初期化が完了しました")
            
        } catch (e: Exception) {
            logger.severe("テーブルの初期化中にエラーが発生しました: ${e.message}")
            e.printStackTrace()
        }
    }
    
}