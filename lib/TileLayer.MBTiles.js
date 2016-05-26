// source: https://github.com/stdavis/OfflineMbTiles/blob/master/www/js/TileLayer.MBTiles.js
module.exports = L.TileLayer.extend({
    mbTilesDB: null,

    initialize: function(url, options, db) {
        this.mbTilesDB = db;
        L.Util.setOptions(this, options);
    },

    _loadTile: function (tile, tilePoint) {

        tile._layer  = this;
        tile.onload  = this._tileOnLoad;
        tile.onerror = this._tileOnError;

        this._adjustTilePoint(tilePoint);

        var z = this._getZoomForUrl();
        var x = tilePoint.x;
        var y = tilePoint.y;

        var base64Prefix = 'data:image/gif;base64,';
        
        $('#status').append('<p id="TileLayerLog"></p>');
        var logText =  $('#TileLayerLog');
        
        
        this.mbTilesDB.transaction(function(tx) {

            logText.html('getTileURL is called with x' + x + ' y ' + y + ' z ' + z);
            tx.executeSql("select * from tiles where zoom_level = ? and tile_column = ? and tile_row = ?;", [z, x, y], function (tx, res) {

                tile.src = base64Prefix + res.rows.item(0).tile_data;

                this.fire('tileloadstart', {
                    tile: tile,
                    url: tile.src
                });
            }, function (e) {
                logText.html('error with executeSql: ', e.message);
            });
        });
    },
});