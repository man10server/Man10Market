package red.man10.man10market

import org.bukkit.Bukkit
import red.man10.man10bank.Man10Bank
import red.man10.man10itembank.ItemBankAPI
import red.man10.man10itembank.util.MySQLManager
import red.man10.man10market.Man10Market.Companion.bankAPI
import red.man10.man10market.Man10Market.Companion.instance
import red.man10.man10market.Util.format
import red.man10.man10market.Util.msg
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import kotlin.math.floor


/**
 * 取引処理
 */
object Market {

    private val transactionQueue = LinkedBlockingQueue<(mysql: MySQLManager) -> Unit>()
    private var transactionThread = Thread { transaction() }
    private val priceCache = ConcurrentHashMap<String, PriceData>()
    private val mysql = MySQLManager(instance, "Man10MarketQueue")

    init {
        runTransactionQueue()
        //キャッシュ読み込み
        getItemIndex().forEach { syncLogTick(it, 0) }
    }


    //登録されているアイテムの識別名を取得
    fun getItemIndex(): List<String> {
        return ItemBankAPI.getItemIndexList()
    }

    fun getItemNumber(item:String):Int{
        return ItemBankAPI.getItemData(item)?.id?:0
    }

    //取引アイテムかどうか
    private fun isMarketItem(item: String): Boolean {
        return getItemIndex().contains(item)
    }

    fun getPrice(item: String): PriceData {
        return priceCache[item] ?: PriceData(item, 0.0, 0.0)
    }


    //価格変更があったら呼び出す
    //TickテーブルにTick履歴の書き込み
    private fun syncLogTick(item: String, volume: Int) {

        val ask: Double
        val bid: Double

        val list = syncGetOrderList(item)

        if (list.isNullOrEmpty()) {
            return
        }

        val sell = list.filter { f -> f.sell }
        val buy = list.filter { f -> f.buy }

        ask = if (sell.isEmpty()) 0.0 else sell.minOf { it.price }
        bid = if (buy.isEmpty()) 0.0 else buy.maxOf { it.price }

        //nullの場合は現在の価格を
        if (priceCache[item] == null) {
            priceCache[item] = PriceData(item, ask, bid)
        }
        val cache = priceCache[item]!!

        //出来高がなく、価格変更がない場合はTickを生成しない
        if (volume == 0 && (cache.ask == ask && cache.bid == bid)) {
            priceCache[item] = PriceData(item, ask, bid)
            ItemBankAPI.setItemPrice(item, bid, ask)
            return
        }

        //キャッシング
        priceCache[item] = PriceData(item, ask, bid)
        ItemBankAPI.setItemPrice(item, bid, ask)

        mysql.execute(
            "INSERT INTO tick_table (item_id, date, bid, ask, volume) " +
                    "VALUES ('${item}', DEFAULT, ${bid}, ${ask}, $volume)"
        )

        //Tickイベント
        MarketData.tickEvent(item,cache,volume)
    }

    //取引があったら呼ぶ
    private fun syncRecordLog(uuid: UUID, item: String, amount: Int, price: Double, type: String) {

        val p = Bukkit.getOfflinePlayer(uuid)
        mysql.execute(
            "INSERT INTO execution_log (player, uuid, item_id, amount, price, exe_type, datetime) " +
                    "VALUES ('${p.name}', '${uuid}', '$item', $amount, ${amount * price}, '${type}', DEFAULT)"
        )
    }

    //指値注文を取得する
    @Synchronized
    private fun syncGetOrderList(item: String): List<OrderData>? {

        if (!isMarketItem(item))
            return null

        val rs = mysql.query("select * from order_table where item_id='${item}';") ?: return null

        val list = mutableListOf<OrderData>()

        while (rs.next()) {

            val data = OrderData(
                UUID.fromString(rs.getString("uuid")),
                rs.getInt("id"),
                item,
                rs.getDouble("price"),
                rs.getInt("lot"),
                rs.getInt("buy") == 1,
                rs.getInt("sell") == 1,
                rs.getDate("entry_date")
            )

            list.add(data)
        }

        rs.close()
        mysql.close()

        return list
    }

    fun getOrderList(item: String, callback: (List<OrderData>?) -> Unit) {
        transactionQueue.add { callback.invoke(syncGetOrderList(item)) }
    }

    fun getUserOrderList(uuid: UUID, callback: (List<OrderData>) -> Unit) {

        transactionQueue.add {

            val rs = mysql.query("select * from order_table where uuid='${uuid}';")

            val list = mutableListOf<OrderData>()

            if (rs == null) {
                callback.invoke(list)
                return@add
            }

            while (rs.next()) {

                val data = OrderData(
                    uuid,
                    rs.getInt("id"),
                    rs.getString("item_id"),
                    rs.getDouble("price"),
                    rs.getInt("lot"),
                    rs.getInt("buy") == 1,
                    rs.getInt("sell") == 1,
                    rs.getDate("entry_date")
                )

                list.add(data)
            }

            rs.close()
            mysql.close()

            callback.invoke(list)
        }
    }


    //成り行き注文
    fun sendMarketBuy(uuid: UUID, item: String, lot: Int, sendInventory: Boolean = false) {

        transactionQueue.add {
            val p = Bukkit.getPlayer(uuid) ?: return@add

            if (lot <= 0) {
                msg(p, "§c§l最低注文個数は1個です")
                return@add
            }

            if (!isMarketItem(item)) {
                msg(p, "§c§l存在しないアイテムです")
                return@add
            }

            //安い順に売り注文を並べる
            var firstOrder: OrderData?

            //残り個数
            var remainAmount = lot

            while (remainAmount > 0) {

                //売り指値の最安値を取得
                firstOrder = syncGetOrderList(item)?.filter { it.sell }?.minByOrNull { it.price }

                //指値が亡くなった
                if (firstOrder == null) {
                    msg(p, "§c§l現在このアイテムの注文はありません")
                    return@add
                }

                //このループで取引する数量
                val tradeAmount = if (firstOrder.lot > remainAmount) remainAmount else firstOrder.lot

                if (!Man10Market.vault.withdraw(p,tradeAmount * firstOrder.price)){
                    msg(p, "§c§ld§n電子マネー§c§ldの残高が足りません！")
                    return@add
                }

                msg(p, "§e電子マネーから${format(tradeAmount*firstOrder.price)}円支払いました")

                if (syncTradeOrder(firstOrder.orderID, tradeAmount) == null) {
                    Bukkit.getLogger().info("ErrorModifyOrder")
                    continue
                }

                if (sendInventory) {
                    val itemData = ItemBankAPI.getItemData(item)!!
                    val itemStack = itemData.item!!.clone()
                    itemStack.amount = tradeAmount

                    if (p?.inventory?.firstEmpty() == -1) {
                        msg(p, "§cインベントリに空きがないため、アイテムバンク(/mib)に収納しました")
                        ItemBankAPI.addItemAmount(uuid, uuid, item, tradeAmount)
                    } else {
                        Bukkit.getScheduler().runTask(instance, Runnable {
                            p?.inventory?.addItem(itemStack)
                            Bukkit.getLogger().info("item:$itemStack hash:${itemStack.hashCode()}")
                        })
                    }

                } else {
                    ItemBankAPI.addItemAmount(uuid, uuid, item, tradeAmount)
                }

                remainAmount -= tradeAmount

                msg(p, "§e§l${tradeAmount}個購入")
                syncRecordLog(uuid, item, tradeAmount, firstOrder.price, "成行買い")
                syncLogTick(item, tradeAmount)
            }
        }
    }

    fun sendMarketSell(uuid: UUID, item: String, lot: Int) {

        transactionQueue.add {

            val p = Bukkit.getPlayer(uuid)?:return@add

            if (lot <= 0) {
                msg(p, "§c§l最低注文個数は1個です")
                return@add
            }

            if (!isMarketItem(item)) {
                msg(p, "§c§l存在しないアイテムです")
                return@add
            }

            //高い順に買い注文を並べる
            var firstOrder: OrderData?

            //残り個数
            var remainAmount = lot

            while (remainAmount > 0) {

                //買い指値の最高値
                firstOrder = syncGetOrderList(item)?.filter { it.buy }?.maxByOrNull { it.price }

                //指値が亡くなった
                if (firstOrder == null) {
                    msg(p, "§c§l現在このアイテムの注文はありません")
                    return@add
                }

                //このループで取引する数量
                val tradeAmount = if (firstOrder.lot > remainAmount) remainAmount else firstOrder.lot

                val lock = Lock()

                var result: Int? = null

                ItemBankAPI.takeItemAmount(uuid, uuid, item, tradeAmount) {
                    result = it
                    lock.unlock()
                }

                lock.lock()

                if (result == null) {
                    msg(p, "§c§lアイテムバンクの在庫が足りません！")
                    return@add
                }

                if (syncTradeOrder(firstOrder.orderID, tradeAmount) == null) {
                    Bukkit.getLogger().info("ErrorModifyOrder")
                    return@add
                }

                Man10Market.vault.deposit(p,tradeAmount * firstOrder.price)

                remainAmount -= tradeAmount

                msg(p, "§e§l${tradeAmount}個売却")
                msg(p, "§e電子マネーに${format(tradeAmount*firstOrder.price)}円追加されました")
                syncRecordLog(uuid, item, tradeAmount, firstOrder.price, "成行売り")
                syncLogTick(item, tradeAmount)
            }
        }
    }

    //指値
    fun sendOrderBuy(uuid: UUID, item: String, lot: Int, price: Double) {

        transactionQueue.add {

            val p = Bukkit.getOfflinePlayer(uuid)

            if (!isMarketItem(item)) {
                msg(p.player, "§c§l登録されていないアイテムです")
                return@add
            }

            if (lot <= 0) {
                msg(p.player, "§c§l注文数を1個以上にしてください")
                return@add
            }

            if (price < 1.0) {
                msg(p.player, "§c§l値段を1円以上にしてください")
                return@add
            }

            if (price!=price.toInt().toDouble()){
                msg(p.player, "§c§l少数以下の設定はできません")
                return@add
            }

            val nowPrice = getPrice(item)
            //売値より高い指値入れられない
            if (price >= nowPrice.ask) {
                msg(p.player, "§c§l売値(${format(nowPrice.ask)}円)より安い値段に設定してください")
                return@add
            }

            val fixedPrice = floor(price)

            val requireMoney = lot * fixedPrice

            if (!bankAPI.withdraw(uuid, requireMoney, "Man10MarketOrderBuy", "マーケット指値買い")) {
                msg(p.player, "§c§l銀行の残高が足りません！(必要金額:${format(requireMoney)})")
                return@add
            }

            mysql.execute(
                "INSERT INTO order_table (player, uuid, item_id, price, buy, sell, lot, entry_date) " +
                        "VALUES ('${p.name}', '${uuid}', '${item}', ${fixedPrice}, 1, 0, ${lot}, DEFAULT)"
            )

            if (fixedPrice > nowPrice.bid) {
                syncLogTick(item, 0)
            }

            msg(p.player, "§b§l指値買§e§lを発注しました")
            syncRecordLog(uuid, item, lot, price, "指値買い")
            syncLogTick(item, 0)

        }

    }

    fun sendOrderSell(uuid: UUID, item: String, lot: Int, price: Double) {

        transactionQueue.add {

            val p = Bukkit.getOfflinePlayer(uuid)

            if (!isMarketItem(item)) {
                msg(p.player, "§c§l登録されていないアイテムです")
                return@add
            }

            if (lot <= 0) {
                msg(p.player, "§c§l注文数を1個以上にしてください")
                return@add
            }

            if (price < 1.0) {
                msg(p.player, "§c§l値段を1円以上にしてください")
                return@add
            }

            if (price!=price.toInt().toDouble()){
                msg(p.player, "§c§l少数以下の設定はできません")
                return@add
            }

            val nowPrice = getPrice(item)

            //買値より安い指値を入れれない
            if (price <= nowPrice.bid) {
                msg(p.player, "§c§l買値(${format(nowPrice.bid)}円)より高い値段に設定してください")
                return@add
            }

            //相場の不正対策
            if (price > nowPrice.bid * 10 && nowPrice.bid != 0.0 && nowPrice.ask == 0.0) {
                msg(p.player, "§c§l売り注文がない状態で買値の10倍以上の価格は注文できません")
                return@add
            }

            val fixedPrice = floor(price)

            ItemBankAPI.takeItemAmount(uuid, uuid, item, lot) {
                if (it == null) {
                    msg(p.player, "§c§lアイテム取り出し失敗！")
                    return@takeItemAmount
                }
                mysql.execute(
                    "INSERT INTO order_table (player, uuid, item_id, price, buy, sell, lot, entry_date) " +
                            "VALUES ('${p.name}', '${uuid}', '${item}', ${fixedPrice}, 0, 1, ${lot}, DEFAULT)"
                )

                if (fixedPrice < nowPrice.ask) {
                    syncLogTick(item, 0)
                }

                msg(p.player, "§c§l指値売§e§lを発注しました")
                syncRecordLog(uuid, item, lot, price, "指値売り")
                syncLogTick(item, 0)

            }
        }

    }


    //指値の削除
    fun cancelOrder(uuid: UUID?, id: Int) {

        transactionQueue.add {

            val rs = mysql.query("select * from order_table where id = $id;")

            if (rs == null || !rs.next()) {
                return@add
            }

            val data = OrderData(
                UUID.fromString(rs.getString("uuid")),
                rs.getInt("id"),
                rs.getString("item_id"),
                rs.getDouble("price"),
                rs.getInt("lot"),
                rs.getInt("buy") == 1,
                rs.getInt("sell") == 1,
                rs.getDate("entry_date")
            )

            rs.close()
            mysql.close()

            if (uuid != null && uuid != data.uuid)
                return@add

            //買い注文のキャンセル
            if (data.buy) {
                bankAPI.deposit(data.uuid, (data.lot * data.price), "CancelMarketOrderBuy", "マーケット指値買いキャンセル")
            }

            if (data.sell) {
                ItemBankAPI.addItemAmount(data.uuid, data.uuid, data.item, data.lot)
            }

            mysql.execute("DELETE from order_table where id = ${id};")

            //値段の変更があるかもしれないので呼ぶ
            syncLogTick(data.item, 0)
            syncRecordLog(data.uuid, data.item, data.lot, data.price, "指値取り消し")

        }


    }

    //成りが入った時に指定個数指値を減らす(null失敗、-1個数問題)
    private fun syncTradeOrder(id: Int, amount: Int): Int? {

        val rs = mysql.query("select * from order_table where id = $id;")

        if (rs == null || !rs.next()) {
            return null
        }

        val data = OrderData(
            UUID.fromString(rs.getString("uuid")),
            rs.getInt("id"),
            rs.getString("item_id"),
            rs.getDouble("price"),
            rs.getInt("lot"),
            rs.getInt("buy") == 1,
            rs.getInt("sell") == 1,
            rs.getDate("entry_date")
        )

        rs.close()
        mysql.close()

        val newAmount = data.lot - amount

        if (newAmount < 0) {
            return -1
        }

        if (newAmount == 0) {
            mysql.execute("DELETE from order_table where id = ${id};")
        } else {
            mysql.execute("UPDATE order_table SET lot = $newAmount WHERE id = ${id};")

        }

        syncLogTick(data.item, amount)

        //指値買い
        if (data.buy) {
            ItemBankAPI.addItemAmount(data.uuid, data.uuid, data.item, amount)
        }

        //指値売り
        if (data.sell) {
            bankAPI.deposit(data.uuid, (amount * data.price), "Man10MarketOrderSell", "マーケット指値売り")
        }

        syncRecordLog(data.uuid, data.item, amount, data.price, "指値調整")

        return newAmount
    }

    /////////////////////////////////
    //注文処理を順番に捌いていくキュー
    //////////////////////////////////

    //      外部クラスからジョブを追加
    fun addJob(job:(mysql:MySQLManager)->Unit){
        transactionQueue.add(job)
    }
    fun runTransactionQueue() {

        //すでに起動していたら止める
        interruptTransactionQueue()

        transactionThread = Thread { transaction() }
        transactionThread.start()
    }

    fun interruptTransactionQueue() {
        if (transactionThread.isAlive)
            transactionThread.interrupt()
    }

    private fun transaction() {

        while (true) {
            try {

                val action = transactionQueue.take()
                action.invoke(mysql)

            } catch (e: InterruptedException) {
                Bukkit.getLogger().info("取引スレッドを停止しました")
                break
            } catch (e: Exception) {
                Bukkit.getLogger().info(e.message)
                e.stackTrace.forEach { Bukkit.getLogger().info("${it.className};${it.methodName};${it.lineNumber}") }
            }
        }
    }

    data class OrderData(
        var uuid: UUID,
        var orderID: Int,
        var item: String,
        var price: Double,
        var lot: Int,
        var buy: Boolean,
        var sell: Boolean,
        var date: Date

    )

    data class PriceData(
        var item: String,
        var ask: Double,
        var bid: Double,
        var price: Double = (ask + bid) / 2
    )

    class Lock {

        @Volatile
        private var isLock = false

        @Volatile
        private var hadLocked = false

        fun lock() {
            synchronized(this) {
                if (hadLocked) {
                    return
                }
                isLock = true
            }
            while (isLock) {
                Thread.sleep(1)
            }
        }

        fun unlock() {
            synchronized(this) {
                hadLocked = true
                isLock = false
            }
        }
    }

}