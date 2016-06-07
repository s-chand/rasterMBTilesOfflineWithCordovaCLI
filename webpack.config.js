var webpack = require("webpack"),
    path = require('path'),
    srcPath = path.join(__dirname, 'src'),
    distPath =  path.join(__dirname, 'rasterMBTilesOfflineWithCordCLI/www/js');

module.exports = {
    entry: {
        app: path.join(srcPath, "index.js")
    },
    output: {
        path: distPath,
        filename: "bundle.js"
    },
    resolve: {
        extensions: ['', '.html', '.js', '.json', '.css'],
        alias: {
            leaflet_css: __dirname + "/node_modules/leaflet/dist/leaflet.css",
            leaflet_layers2x: __dirname + "/node_modules/leaflet/dist/images/layers-2x.png",
            leaflet_layers: __dirname + "/node_modules/leaflet/dist/images/layers.png",
            leaflet_marker: __dirname + "/node_modules/leaflet/dist/images/marker-icon.png",
            leaflet_marker_2x: __dirname + "/node_modules/leaflet/dist/images/marker-icon-2x.png",
            leaflet_marker_shadow: __dirname + "/node_modules/leaflet/dist/images/marker-shadow.png",
        }
    },
    module: {
        loaders: [
            { test: /\.css$/, loader: "style!css!" },
            {test: /\.(png|jpg)$/, loader: "file-loader?name=images/[name].[ext]"}
        ]
    },
    plugins: [
        new webpack.ProvidePlugin({
            $: "jquery",
            jQuery: "jquery",
            "windows.jQuery": "jquery"
        })
    ]
};