package red.man10.man10market.stock

import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.OfflinePlayer
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import red.man10.man10itembank.ItemBankAPI
import red.man10.man10itembank.util.MySQLManager
import red.man10.man10market.Command.OP
import red.man10.man10market.Command.USER
import red.man10.man10market.Man10Market.Companion.bankAPI
import red.man10.man10market.Man10Market.Companion.instance
import red.man10.man10market.Util.format
import red.man10.man10market.Util.msg
import java.util.*
import java.util.concurrent.Executors

object Stock : CommandExecutor{

    private val stockList = mutableListOf<StockData>()
    private val thread = Executors.newSingleThreadExecutor()

    init {
        //登録済み株式の読み込み

        thread.execute {
            val mysql = MySQLManager(instance,"Man10MarketStock")

            val rs = mysql.query("select * from stock_table;")?:return@execute

            while (rs.next()){
                val data = StockData(
                    rs.getString("stock_name"),
                    UUID.fromString(rs.getString("uuid")),
                    rs.getInt("total_issued_stock"),
                    rs.getDate("last_issue_date")
                )

                stockList.add(data)
            }

            rs.close()
            mysql.close()

        }
    }

    private fun asyncRegister(p:Player, owner:OfflinePlayer, name:String){

        if (isStock(name)){
            msg(p,"同一名のアイテムがすでに登録されています")
            return
        }

        Bukkit.getScheduler().runTask(instance, Runnable {
            p.performCommand("mibop register $name 10 1")
        })

        val data = StockData(name,owner.uniqueId,0,Date())
        stockList.add(data)

        val mysql = MySQLManager(instance,"Man10MarketStock")
        mysql.execute("INSERT INTO stock_table (player, uuid, stock_name, total_issued_stock, last_issue_date) " +
                "VALUES ('${owner.name}', '${owner.uniqueId}', '${name}', 0, now());")

        msg(p,"§e§l発行手続き完了")
    }

    private fun issueStock(p:Player, name:String, amount:Int){

        if (!isStock(name)){
            msg(p,"§c§lこのアイテムは株ではありません！")
            return
        }

        if (getStockOwner(name)!=p.uniqueId){
            msg(p,"§c§lこの株式の管理人はあなたではありません")
            return
        }

        val data = stockList.first { it.name == name }
        stockList.remove(data)
        data.totalIssue = data.totalIssue+amount
        stockList.add(data)


        ItemBankAPI.addItemAmount(p.uniqueId,p.uniqueId,name,amount){
            thread.execute{
                val mysql = MySQLManager(instance,"Man10MarketStock")
                mysql.execute("UPDATE stock_table SET total_issued_stock = total_issued_stock+${amount},last_issue_date=now() WHERE stock_name = '${name}';")
                msg(p,"§e§l株式を発行し、mibに保存しました")

                asyncGetShareholder(name)!!.forEach {
                    val pp = Bukkit.getOfflinePlayer(it.first).player
                    msg(pp,"§e§l${name}が株式を新規で${amount}株発行しました")
                }
            }

        }
    }

    private fun isStock(name:String):Boolean{
        return stockList.any { it.name == name }
    }

    private fun getStockOwner(name: String):UUID?{
        return stockList.firstOrNull { it.name == name }?.uuid
    }

    private fun getIssuedStock(name: String): Int? {
        return stockList.firstOrNull { it.name == name }?.totalIssue
    }
    //配当を支払う
    private fun asyncPay(p:Player, name:String, amount:Double){

        if (!isStock(name)){
            msg(p,"§c§lこのアイテムは株ではありません！")
            return
        }

        if (getStockOwner(name)!! != p.uniqueId){
            msg(p,"§c§lこの株式の管理人はあなたではありません")
            return
        }

        val shareholder = asyncGetShareholder(name)!!

        val totalStock = shareholder.sumOf { it.second }

        if (!bankAPI.withdraw(p.uniqueId,totalStock*amount,"PayDividend","配当金の支払い:${name}")){
            msg(p,"配当金を支払うために必要な金額は${format(totalStock*amount)}円です")
            return
        }

        shareholder.forEach {
            bankAPI.deposit(it.first,it.second*amount,"PayDividend","配当金の支払い:${name}")
        }

        msg(p,"配当金支払い完了")
    }

    //所有者と保有株数を取得
    private fun asyncGetShareholder(name:String): List<Pair<UUID, Int>>? {

        if (!isStock(name)){ return null }

        val map = HashMap<UUID,Int>()

        val mysql = MySQLManager(instance, "Man10MarketStock")
        val rs = mysql.query("select uuid,amount from item_storage where item_key='$name';") ?: return emptyList()

        while (rs.next()){
            map[UUID.fromString(rs.getString("uuid"))] = rs.getInt("amount")
        }

        rs.close()
        mysql.close()

        val rs2 = mysql.query("select uuid,lot from order_table where item_id='$name' and sell=1;") ?: return emptyList()

        while (rs2.next()){
            val uuid = UUID.fromString(rs2.getString("uuid"))
            map[uuid] = (map[uuid]?:0) + rs2.getInt("lot")
        }

        rs2.close()
        mysql.close()

        val list = mutableListOf<Pair<UUID,Int>>()

        for (data in map){
            list.add(Pair(data.key,data.value))
        }

        return list
    }

    //サンプル発行
    private fun createStockItem(title:String,lore:List<String>):ItemStack{

        val stockItem = ItemStack(Material.PAPER)
        val meta = stockItem.itemMeta
        meta.setCustomModelData(2)

        val fixLore = lore.toMutableList()

        fixLore.add("§c[この株券は所有者が§7${title}§cの株主であることを証する]")
        fixLore.add("§c[mibに保管することによって効力を発する]")

        meta.displayName(Component.text("§7§l${title}"))
        meta.lore = fixLore

        stockItem.itemMeta = meta

        return stockItem
    }

    private fun showStockData(p:Player, name:String){

        if (!isStock(name)){
            msg(p,"§c§l登録されていない株式です")
            return
        }

        msg(p,"§e§l登録名:${name}")

        val owner = Bukkit.getOfflinePlayer(getStockOwner(name)!!)

        msg(p,"§e§l管理人:${owner.name}")
        msg(p,"§e§l総発行数:${getIssuedStock(name)?:0}株")

    }

    data class StockData(
        var name: String,
        var uuid: UUID,
        var totalIssue : Int,
        var lastIssue : Date
    )

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {


        if (label!="mstock")return false
        if (sender !is Player)return true


        if (!sender.hasPermission(USER)){
            return true
        }

        if (args.isEmpty()){
            msg(sender,"/mstock issue <株式名> <発行数> : 資金調達のために株式を新規発行する(株主と要相談)")
            msg(sender,"/mstock pay <株式名> <一株あたりの配当額> : 株主に配当を支払う")
            msg(sender,"/mstock show <株式名> : 総発行株数などを確認する")
            return true
        }

        when(args[0]){

            "register" ->{
                if (!sender.hasPermission(OP)){
                    return true
                }

                if (args.size!=3){
                    msg(sender,"/mstock register <owner> <name>")
                    return true
                }

                val owner = Bukkit.getOfflinePlayer(Bukkit.getPlayer(args[1])!!.uniqueId)

                thread.execute{
                    asyncRegister(sender,owner,args[2])
                }
            }

            "sample" ->{

                if (!sender.hasPermission(OP)){
                    return true
                }

                if (args.size!=3){
                    msg(sender,"/mstock sample <title> <lore1/lore2>")
                    return true
                }

                val title = args[1]
                val lore = args[2].replace("&","§").split("/").toList()

                sender.inventory.addItem(createStockItem(title,lore))

                return true
            }

            "show" ->{
                showStockData(sender,args[1])
            }

            "issue" ->{

                if (args.size!=3){
                    msg(sender,"§c§l/mstock issue <株式名> <新規発行数>")
                    return true
                }

                val amount = args[2].toIntOrNull()

                if (amount == null || amount<=0){
                    msg(sender,"§c§l発行株数は1株以上にしてください")
                    return true
                }

                issueStock(sender,args[1],amount)
            }

            "pay" ->{
                if (args.size!=3){
                    msg(sender,"§c§l/mstock issue <株式名> <一株あたりの配当額>")
                    return true
                }

                val amount = args[2].toDoubleOrNull()

                if (amount == null || amount<=0){
                    msg(sender,"§c§l配当金額は1円以上にしてください")
                    return true
                }

                thread.execute{
                    asyncPay(sender,args[1],amount)
                }

            }


        }

        return false
    }

}
