package red.man10.man10market.map;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import red.man10.man10itembank.ItemBankAPI;
import red.man10.man10market.Man10Market;
import red.man10.man10market.Market;
import red.man10.man10market.Util;

import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class PriceMap {

    private static Font baseFont;

    //価格情報のキャッシュ
    private static final Map<Integer, Market.PriceData> priceCache = new HashMap<>();

    public static void getPriceMap(Player p, String item) {

        int id = ItemBankAPI.INSTANCE.getItemData(item).getId();

        ItemStack map = MappRenderer.getMapItem(Man10Market.instance, "price:" + id);

        if (map == null) {
            p.sendMessage("Error Null");
            return;
        }

        p.getInventory().addItem(map);
    }

    public static void registerPriceMap() {
        loadFont(Man10Market.instance);

        Bukkit.getLogger().info("Priceマップ登録");

        try {

            List<String> items = Market.INSTANCE.getItemIndex();

            for (String item : items) {

                int id = ItemBankAPI.INSTANCE.getItemData(item).getId();

                //描画処理
                MappRenderer.draw("price:" + id, 600, (String key, int mapId, Graphics2D g) -> drawPrice(g, item,mapId));

                //マップタッチ処理
                MappRenderer.displayTouchEvent("price:" + id, (key, mapId, player, x, y) -> {
                    player.performCommand("mce price " + item);
                    return true;
                });
            }

        } catch (Exception e) {
            Bukkit.getLogger().info(e.getMessage());
            System.out.println(e.getMessage());
        }

        Bukkit.getLogger().info("Priceマップ登録 完了");

    }

    static boolean drawTrade(Graphics2D g, String item, int id){

        Market.PriceData cache = priceCache.get(id);
        Market.PriceData price = Market.INSTANCE.getPrice(item);

        //価格変化がなかった場合は、更新をしない
        if (cache != null && cache.getPrice() == price.getPrice()){
            return false;
        }

        priceCache.put(id,price);

        g.setColor(Color.GRAY);
        g.fillRect(0, 0, 128, 128);

        int itemID = ItemBankAPI.INSTANCE.getItemData(item).getId();

        MappDraw.drawImage(g,String.valueOf(itemID),72,10,32,32);

        g.setColor(Color.WHITE);

        int titleSize = 13;

        g.setFont(baseFont.deriveFont(Font.BOLD, titleSize));

        MappDraw.drawShadowString(g, item, Color.WHITE, Color.BLACK, 5, 20);

        g.setColor(Color.YELLOW);
        g.fillRoundRect(16,40,44,80,8,8);
        g.fillRoundRect(69,40,44,80,8,8);

        return true;
    }

    //      現在値を表示
    static boolean drawPrice(Graphics2D g, String item,int id) {

        Market.PriceData cache = priceCache.get(id);
        Market.PriceData price = Market.INSTANCE.getPrice(item);

        //価格変化がなかった場合は、更新をしない
        if (cache != null && cache.getPrice() == price.getPrice()){
            return false;
        }

        priceCache.put(id,price);

        g.setColor(Color.GRAY);
        g.fillRect(0, 0, 128, 128);

        int itemID = ItemBankAPI.INSTANCE.getItemData(item).getId();

        MappDraw.drawImage(g,String.valueOf(itemID),64,20,64,64);

        g.setColor(Color.WHITE);

        int titleSize = 20;
        if (item.length() > 6) {
            titleSize = 12;
        }

        g.setFont(baseFont.deriveFont(Font.BOLD, titleSize));

        MappDraw.drawShadowString(g, item, Color.WHITE, Color.BLACK, 5, 20);

        g.setFont(baseFont.deriveFont(Font.BOLD, 20));

        String strPrice = Util.INSTANCE.format(price.getPrice(), 0);

        if (price.getBid() == 0 || price.getAsk() == Double.MAX_VALUE) {
            strPrice = "仲直未定";
        }

        MappDraw.drawShadowString(g, strPrice, Color.YELLOW, Color.BLACK, 10, 50);

        g.setColor(Color.GREEN);
        g.setFont(baseFont.deriveFont(Font.BOLD, 16));

        if (price.getAsk() == Double.MAX_VALUE) {
            MappDraw.drawShadowString(g, "売り注文なし", Color.GREEN, Color.black, 4, 80);
        } else {
            g.setFont(baseFont.deriveFont(Font.BOLD, 16));
            g.drawString("買:" + Util.INSTANCE.format(price.getAsk(), 0), 4, 80);
        }

        g.setColor(Color.RED);

        if (price.getBid() == 0) {
            MappDraw.drawShadowString(g, "買い注文なし", Color.RED, Color.black, 4, 100);
        } else {

            g.drawString("売:" + Util.INSTANCE.format(price.getBid(), 0), 4, 98);
        }

        return true;

    }

    private static void loadFont(Man10Market plugin) {
        plugin.getLogger().info("フォントファイルの読み込み");
        try {
            File fontFile = new File(plugin.getDataFolder(),"font.ttf");
            baseFont = Font.createFont(Font.TRUETYPE_FONT, fontFile);
        } catch (IOException e){
            plugin.getLogger().warning("フォントファイル読み込みエラー" + e.getMessage());
        } catch (FontFormatException e) {
            plugin.getLogger().warning("フォントのフォーマットエラー" + e.getMessage());
        }
    }
}
