var UTILS = require('./utils');
require('leaflet');
require('leaflet_css');
require('leaflet_layers2x');

L.TileLayer.MBTiles = require('../lib/TileLayer.MBTiles');

$(document).ready(function () {
    document.addEventListener("deviceready", onDeviceReady, true); // this gets called once phonegap/cordova api is running
});

function onDeviceReady() {
    var db;
    UTILS.logIt('accessing db');
     db = window.sqlitePlugin.openDatabase({
            name: "ethiopiatwelve.mbtiles",
            location: "default", // in www folder
            createFromLocation: 1,
            androidDatabaseImplementation: 2
        },
        function () {
            UTILS.logIt('Database opened');


            var mbTiles = new L.TileLayer.MBTiles(
                '', {
                    maxZoom: 15, // TODO: once all this works, min/max-zoom should be determined dynamically
                    tms : true
                }, db);
            

            var map = L.map('map', {
                center: [12.7602, 35.63433558],
                zoom: 12,
                layers: [ mbTiles]
            });


        },
        function () {
            UTILS.logIt('ERROR could not open db: ' + JSON.stringify(err));
        }
    );
}