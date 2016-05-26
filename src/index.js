

var UTILS = require('./utils');
require('leaflet');
require('leaflet_css');


L.TileLayer.MBTiles = require('../lib/TileLayer.MBTiles');

$(document).ready(function () {
    document.addEventListener("deviceready", onDeviceReady, true); // this gets called once phonegap/cordova api is running
});
function onDeviceReady() {

    UTILS.logIt('accessing db');
    var db = window.sqlitePlugin.openDatabase({name: "tiles.db",
        location: 'default', // in www folder
        createFromLocation: 1,
        androidLockWorkaround: 1, // https://github.com/trevorpowell/cordova-sqlite-ext#workaround-for-android-db-locking-issue
        androidDatabaseImplementation: 2},
        function () {
            UTILS.logIt('DB opened')
            db.transaction(function(tx) {
                tx.executeSql("SELECT * FROM  tiles LIMIT 10", [], function (tx, res) {
                    UTILS.logIt("stringify: " + JSON.stringify(res.rows.item(0)));
                }, function(error) {
                    console.log('SELECT error: ' + error.message);
                });
            });
            var mbTiles = new L.TileLayer.MBTiles(
                '', {
                    maxZoom: 15, // TODO: once all this works, min/max-zoom should be determined dynamically
                    tms : true
                }, db);

            var map = L.map('map', {
                center: [8.65, 39.54],
                zoom: 7,
                layers: [mbTiles]
            });

        },
        function () {
            UTILS.logIt('ERROR could not open db: ' + JSON.stringify(err));
        }
    );





    
    

}